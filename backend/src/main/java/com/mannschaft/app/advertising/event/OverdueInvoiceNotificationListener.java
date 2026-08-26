package com.mannschaft.app.advertising.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 広告請求書延滞通知の配送リスナー（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code OverdueInvoiceMarkRunner#markOne} の独立トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>複数受信者</b>の金型として
 * 第1群の {@code OnboardingReminderNotificationListener} と同型
 * （受信者リストの解決は Runner 側で 1 回、組み立て＋配送は受信者ごとの内側 try で隔離）。</p>
 *
 * <h2>削除済み source を参照しないことの確認</h2>
 * <p>{@code sourceType=AD_INVOICE} / {@code sourceId=請求書ID} を参照するが、OVERDUE 遷移は
 * 請求書行を削除も論理削除もしない（{@code status} 列の更新のみ）。よってコミット後発火でも
 * source は生存しており「静かな deny」は発生しない。</p>
 *
 * <h2>メール送信を通知と別の内側 try に分ける理由</h2>
 * <p>outbox への enqueue が失敗しても、その受信者の<b>アプリ内通知は成功している</b>。
 * 同じ try にまとめると成功した通知まで failed として数えられ、
 * 「deny と例外を区別して観測する」方針の解像度が落ちる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverdueInvoiceNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final EmailOutboxService emailOutboxService;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = {"FEATURE_PROMOTION_ENABLED"},
            reason = "広告・販促は棚卸し台帳で beta=停止・gate_key=FEATURE_PROMOTION_ENABLED を持つ隔離対象であり、"
                    + "機能停止中に延滞通知だけが利用者へ飛ぶことを避ける。延滞判定は毎日 06:00 のバッチで"
                    + "再走査されるため、ドロップしたイベントが再生されなくても機能再開後の実害は残らない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOverdueInvoiceNotification(OverdueInvoiceNotificationEvent event) {
        List<OverdueInvoiceNotificationEvent.Recipient> orgAdmins =
                event.organizationAdmins() == null ? List.of() : event.organizationAdmins();
        List<Long> systemAdmins =
                event.systemAdminUserIds() == null ? List.of() : event.systemAdminUserIds();
        if (orgAdmins.isEmpty() && systemAdmins.isEmpty()) {
            return;
        }

        // locale は全受信者ぶんを 1 回で解決する（N+1 防止）。解決自体の失敗は既定 locale で継続する。
        List<Long> allUserIds = new ArrayList<>(orgAdmins.stream()
                .map(OverdueInvoiceNotificationEvent.Recipient::userId).toList());
        allUserIds.addAll(systemAdmins);
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(allUserIds);
        } catch (Exception e) {
            log.warn("請求書延滞通知の locale 一括解決に失敗（既定 locale で継続）: invoiceId={}, error={}",
                    event.invoiceId(), e.getMessage());
            locales = Map.of();
        }

        int denied = 0;
        int failed = 0;
        int emailFailed = 0;
        Long firstFailedUserId = null;

        for (OverdueInvoiceNotificationEvent.Recipient recipient : orgAdmins) {
            Locale locale = Locale.forLanguageTag(locales.getOrDefault(recipient.userId(), "ja"));
            try {
                // 組み立ても受信者単位で内側 try に入れる（1人ぶんの組み立て失敗が他を巻き添えにしない）。
                NotificationDeliveryRequest request = buildRequest(
                        recipient.userId(), event, NotificationScopeType.ORGANIZATION, event.organizationId(),
                        "/advertiser/invoices/" + event.invoiceId(), locale);
                NotificationEntity created = notificationDeliveryRunner.sendOne(request);
                if (created == null) {
                    denied++;
                    log.warn("請求書延滞通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = recipient.userId();
                }
                log.error("請求書延滞通知の配送に失敗しました: recipientUserId={}, invoiceId={}, organizationId={}",
                        recipient.userId(), event.invoiceId(), event.organizationId(), e);
            }

            if (recipient.email() == null || recipient.email().isBlank()) {
                continue;
            }
            try {
                emailOutboxService.enqueue(new EmailOutboxRequest(
                        "ADVERTISING_INVOICE_OVERDUE",
                        "ja",
                        recipient.email(),
                        Map.of("subject", "請求書延滞通知", "body", buildOverdueEmailHtml(event)),
                        "advertising",
                        "invoice-overdue:" + event.invoiceId(),
                        null,
                        recipient.userId(),
                        null));
            } catch (Exception e) {
                emailFailed++;
                log.error("請求書延滞メールの outbox 登録に失敗しました: recipientUserId={}, invoiceId={}",
                        recipient.userId(), event.invoiceId(), e);
            }
        }

        for (Long adminUserId : systemAdmins) {
            try {
                NotificationDeliveryRequest request = buildRequest(
                        adminUserId, event, NotificationScopeType.SYSTEM, null,
                        "/system-admin/invoices/" + event.invoiceId(),
                        Locale.forLanguageTag(locales.getOrDefault(adminUserId, "ja")));
                NotificationEntity created = notificationDeliveryRunner.sendOne(request);
                if (created == null) {
                    denied++;
                    log.warn("請求書延滞通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = adminUserId;
                }
                log.error("請求書延滞通知の配送に失敗しました（SYSTEM_ADMIN 宛）: recipientUserId={}, invoiceId={}",
                        adminUserId, event.invoiceId(), e);
            }
        }

        // 集計ログのレベルは個別ログと揃える。deny は正常系なので WARN、例外が1件でもあれば ERROR。
        if (failed > 0 || emailFailed > 0 || denied > 0) {
            String summary = "請求書延滞通知の一括配送結果: invoiceId={}, total={}, failed={}, emailFailed={}, "
                    + "denied={}, firstFailedUserId={}";
            int total = orgAdmins.size() + systemAdmins.size();
            if (failed > 0 || emailFailed > 0) {
                log.error(summary, event.invoiceId(), total, failed, emailFailed, denied, firstFailedUserId);
            } else {
                log.warn(summary, event.invoiceId(), total, failed, emailFailed, denied, firstFailedUserId);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            Long recipientUserId, OverdueInvoiceNotificationEvent event,
            NotificationScopeType scopeType, Long scopeId, String actionUrl, Locale locale) {
        return new NotificationDeliveryRequest(
                recipientUserId,
                "INVOICE_OVERDUE",
                NotificationPriority.HIGH,
                messageSource.getMessage(
                        "notification.advertising.invoiceOverdue.title", null,
                        "請求書延滞通知", locale),
                messageSource.getMessage(
                        "notification.advertising.invoiceOverdue.body",
                        new Object[]{event.invoiceNumber(), event.dueDate()},
                        "請求書 " + event.invoiceNumber() + "（期限: " + event.dueDate()
                                + "）が延滞状態になりました。",
                        locale),
                "AD_INVOICE",
                event.invoiceId(),
                scopeType,
                scopeId,
                actionUrl,
                null);
    }

    /** メール本文（是正前の {@code buildOverdueEmailHtml} をそのまま移送。文言・"ja" 固定は本ロットの対象外）。 */
    private String buildOverdueEmailHtml(OverdueInvoiceNotificationEvent event) {
        return String.format("""
                <html><body>
                <p>請求書 <strong>%s</strong>（支払期限: %s）が延滞状態になりました。</p>
                <p>お早めにお支払い手続きをお願いいたします。</p>
                <p><a href="/advertiser/invoices/%d">請求書を確認する</a></p>
                </body></html>
                """,
                event.invoiceNumber(),
                event.dueDate(),
                event.invoiceId());
    }
}
