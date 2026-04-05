package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import com.mannschaft.app.advertising.repository.AdvertiserAccountRepository;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OverdueInvoiceBatchService {

    private final AdInvoiceRepository adInvoiceRepository;
    private final AdvertiserAccountRepository advertiserAccountRepository;
    private final EmailService emailService;
    private final NotificationService notificationService;
    private final UserRoleRepository userRoleRepository;

    /**
     * OVERDUE 自動化バッチ。毎日 AM 6:00 (JST) に実行。
     * status = ISSUED かつ due_date < TODAY の請求書を OVERDUE に更新。
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Tokyo")
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
            String title = "請求書延滞通知";
            String body = String.format("請求書 %s（期限: %s）が延滞状態になりました。",
                    invoice.getInvoiceNumber(), invoice.getDueDate());

            // 広告主組織のADMINユーザーへ通知
            advertiserAccountRepository.findById(invoice.getAdvertiserAccountId())
                    .ifPresent(account -> {
                        Long orgId = account.getOrganizationId();
                        List<Object[]> orgAdmins = userRoleRepository.findUserIdAndEmailByScopeAndRole(
                                "ORGANIZATION", orgId, "ADMIN");
                        for (Object[] row : orgAdmins) {
                            Long userId = ((Number) row[0]).longValue();
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
                                emailService.sendEmail(email, title, htmlBody);
                            }
                        }
                    });

            // SYSTEM_ADMIN へのプッシュ通知
            List<Long> systemAdmins = userRoleRepository.findSystemAdminUserIds();
            for (Long adminUserId : systemAdmins) {
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
