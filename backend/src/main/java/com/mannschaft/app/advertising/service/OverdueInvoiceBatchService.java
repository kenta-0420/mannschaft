package com.mannschaft.app.advertising.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverdueInvoiceBatchService {

    private final AdInvoiceRepository adInvoiceRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final EmailOutboxService emailOutboxService;
    private final NotificationService notificationService;
    private final UserRoleRepository userRoleRepository;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /**
     * OVERDUE 自動化バッチ。毎日 AM 6:00 (JST) に実行。
     * status = ISSUED かつ due_date < TODAY の請求書を OVERDUE に更新。
     */
    @BatchEndpoint(name = "advertising-invoice-overdue-mark-daily", description = "支払期限切れの広告請求書を OVERDUE に更新する（毎日 06:00）")
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "overdueInvoiceMark", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void markOverdueInvoices() {
        LocalDate today = LocalDate.now();
        List<AdInvoiceEntity> overdueInvoices =
                adInvoiceRepository.findByStatusAndDueDateBefore(InvoiceStatus.ISSUED, today);

        if (overdueInvoices.isEmpty()) {
            return;
        }

        log.info("OVERDUE バッチ開始: 対象件数={}", overdueInvoices.size());

        int count = 0;
        for (AdInvoiceEntity invoice : overdueInvoices) {
            try {
                invoice.markOverdue();
                count++;
                sendOverdueNotifications(invoice);
            } catch (Exception e) {
                log.error("OVERDUE 更新エラー: invoiceId={}", invoice.getId(), e);
            }
        }

        log.info("OVERDUE バッチ完了: 更新件数={}", count);
    }

    /**
     * 延滞通知を送信する。
     * 広告主組織のADMINユーザーとSYSTEM_ADMINにプッシュ通知を送る。
     */
    private void sendOverdueNotifications(AdInvoiceEntity invoice) {
        try {
            // メール件名・本文（htmlBody 含む）は本ロットの対象外（notify/createNotification へ渡す
            // 通知の件名・本文のみが AC-1 の対象）。メール本文は "ja" 固定のまま。
            String emailTitle = "請求書延滞通知";

            // 広告主スコープのADMINユーザーへ通知（scopeId = organization_id または team_id）
            advertiserAccountRepository.findById(invoice.getAdvertiserAccountId())
                    .ifPresent(account -> {
                        Long orgId = account.getScopeId();
                        List<Object[]> orgAdmins = userRoleRepository.findUserIdAndEmailByScopeAndRole(
                                "ORGANIZATION", orgId, "ADMIN");
                        // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、
                        // ループの外で一括解決する（N+1 防止）。
                        List<Long> orgAdminIds = orgAdmins.stream()
                                .map(row -> ((Number) row[0]).longValue())
                                .toList();
                        Map<Long, String> orgLocales = userLocaleCache.getLocales(orgAdminIds);
                        for (Object[] row : orgAdmins) {
                            Long userId = ((Number) row[0]).longValue();
                            Locale locale = Locale.forLanguageTag(orgLocales.getOrDefault(userId, "ja"));
                            String title = messageSource.getMessage(
                                    "notification.advertising.invoiceOverdue.title", null,
                                    "請求書延滞通知", locale);
                            String body = messageSource.getMessage(
                                    "notification.advertising.invoiceOverdue.body",
                                    new Object[]{invoice.getInvoiceNumber(), invoice.getDueDate()},
                                    "請求書 " + invoice.getInvoiceNumber() + "（期限: " + invoice.getDueDate()
                                            + "）が延滞状態になりました。",
                                    locale);
                            notificationService.createNotification(
                                    userId, "INVOICE_OVERDUE", NotificationPriority.HIGH,
                                    title, body,
                                    "AD_INVOICE", invoice.getId(),
                                    NotificationScopeType.ORGANIZATION, orgId,
                                    "/advertiser/invoices/" + invoice.getId(), null
                            );
                            String email = (String) row[1];
                            if (email != null && !email.isBlank()) {
                                String htmlBody = buildOverdueEmailHtml(invoice);
                                emailOutboxService.enqueue(new EmailOutboxRequest(
                                        "ADVERTISING_INVOICE_OVERDUE",
                                        "ja",
                                        email,
                                        Map.of("subject", emailTitle, "body", htmlBody),
                                        "advertising",
                                        "invoice-overdue:" + invoice.getId(),
                                        null,
                                        userId,
                                        null
                                ));
                            }
                        }
                    });

            // SYSTEM_ADMIN へのプッシュ通知
            List<Long> systemAdmins = userRoleRepository.findSystemAdminUserIds();
            Map<Long, String> systemAdminLocales = userLocaleCache.getLocales(systemAdmins);
            for (Long adminUserId : systemAdmins) {
                Locale locale = Locale.forLanguageTag(systemAdminLocales.getOrDefault(adminUserId, "ja"));
                String title = messageSource.getMessage(
                        "notification.advertising.invoiceOverdue.title", null,
                        "請求書延滞通知", locale);
                String body = messageSource.getMessage(
                        "notification.advertising.invoiceOverdue.body",
                        new Object[]{invoice.getInvoiceNumber(), invoice.getDueDate()},
                        "請求書 " + invoice.getInvoiceNumber() + "（期限: " + invoice.getDueDate()
                                + "）が延滞状態になりました。",
                        locale);
                notificationService.createNotification(
                        adminUserId, "INVOICE_OVERDUE", NotificationPriority.HIGH,
                        title, body,
                        "AD_INVOICE", invoice.getId(),
                        NotificationScopeType.SYSTEM, null,
                        "/system-admin/invoices/" + invoice.getId(), null
                );
            }
        } catch (Exception e) {
            // 通知送信失敗はバッチ処理全体を止めない
            log.warn("延滞通知の送信に失敗しました: invoiceId={}", invoice.getId(), e);
        }
    }

    private String buildOverdueEmailHtml(AdInvoiceEntity invoice) {
        return String.format("""
                <html><body>
                <p>請求書 <strong>%s</strong>（支払期限: %s）が延滞状態になりました。</p>
                <p>お早めにお支払い手続きをお願いいたします。</p>
                <p><a href="/advertiser/invoices/%d">請求書を確認する</a></p>
                </body></html>
                """,
                invoice.getInvoiceNumber(),
                invoice.getDueDate(),
                invoice.getId());
    }
}
