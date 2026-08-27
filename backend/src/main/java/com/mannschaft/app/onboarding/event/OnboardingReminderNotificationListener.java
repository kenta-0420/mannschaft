package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * オンボーディング手動一括リマインダーの通知配送リスナー（Issue #2834 / CMP-056 第1群ロットA）。
 *
 * <p>{@code OnboardingProgressService#sendReminders} の業務トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>複数受信者</b>の金型として
 * 型確立PR #2910 の {@code EventAdvanceNoticeNotificationListener} と同型
 * （受信者リストの解決は全体で1回・外側 try、受信者ごとに組み立て＋配送を内側 try で隔離）。</p>
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code sendReminders} の {@code @Transactional} 内で受信者をループし、
 * {@code notificationHelper.notify} の失敗を1件ずつ catch して継続していた。{@code createNotification}
 * は既定の {@code REQUIRED} 伝播で業務トランザクションに参加するため、1 受信者の DB 例外が
 * rollback-only を残し、catch して続行した<b>他の受信者の通知もコミット時にまとめて消えていた</b>
 * （{@code UnexpectedRollbackException}。#2655 / #2660 / #2664 と同型）。</p>
 *
 * <h2>ロケールのバルク解決は全体で1回（外側 try）</h2>
 * <p>受信者数に比例した DB 往復（N+1）を防ぐため {@link UserLocaleCache#getLocales} で一括解決する。
 * バルク解決自体が失敗した場合は既定 locale（{@code "ja"}）で全員ぶん継続する（PR #2873 の
 * Codex 検分是正で入った挙動を維持する）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "オンボーディングは棚卸し台帳に独立した gate_key を持たない常時提供の導入導線であり、リマインド通知だけを止める停止条件が存在しないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOnboardingReminderNotification(OnboardingReminderNotificationEvent event) {
        if (event.recipients() == null || event.recipients().isEmpty()) {
            return;
        }

        // 受信者リスト共通の前処理は全体で1回。失敗しても既定 locale で継続できるため配送は止めない。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(
                    event.recipients().stream().map(OnboardingReminderNotificationEvent.Recipient::userId).toList());
        } catch (Exception e) {
            log.warn("オンボーディングリマインドの locale 一括解決に失敗（既定 locale で継続）: "
                    + "scopeType={}, scopeId={}, error={}", event.scopeType(), event.scopeId(), e.getMessage());
            locales = Map.of();
        }

        NotificationScopeType notifScope = "TEAM".equals(event.scopeType())
                ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;

        int denied = 0;
        int failed = 0;
        Long firstFailedProgressId = null;
        for (OnboardingReminderNotificationEvent.Recipient recipient : event.recipients()) {
            try {
                // 組み立ても受信者単位で内側 try に入れる（1人ぶんの組み立て失敗が他を巻き添えにしない）。
                NotificationDeliveryRequest request = buildRequest(
                        recipient, notifScope, event.scopeId(),
                        Locale.forLanguageTag(locales.getOrDefault(recipient.userId(), "ja")));
                NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
                if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                    // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                    denied++;
                    log.warn("オンボーディングリマインド通知が visibility deny によりスキップされました: "
                                    + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                            request.recipientUserId(), request.notificationType(),
                            request.sourceType(), request.sourceId());
                }
            } catch (Exception e) {
                failed++;
                if (firstFailedProgressId == null) {
                    firstFailedProgressId = recipient.progressId();
                }
                log.error("オンボーディングリマインド通知の配送に失敗しました: "
                                + "recipientUserId={}, progressId={}, scopeType={}, scopeId={}",
                        recipient.userId(), recipient.progressId(), event.scopeType(), event.scopeId(), e);
            }
        }

        // 集計ログのレベルは個別ログと揃える。visibility deny は例外ではなく正常系なので
        // WARN に留め、例外が1件でもあるときだけ ERROR（非同期イベント失敗の監査記録・規約上必須）とする。
        // 両者を ERROR に混ぜると「deny（WARN）と例外（ERROR）を区別して観測できる」という方針が潰れる。
        if (failed > 0 || denied > 0) {
            // 1件ずつ大量記録せず、イベント単位で失敗数と代表IDをまとめる
            // （確定設計「複数件イベントなら…代表IDをまとめる」）。
            String summary = "オンボーディングリマインド一括配送の結果: scopeType={}, scopeId={}, "
                    + "total={}, failed={}, denied={}, firstFailedProgressId={}";
            if (failed > 0) {
                log.error(summary, event.scopeType(), event.scopeId(), event.recipients().size(),
                        failed, denied, firstFailedProgressId);
            } else {
                log.warn(summary, event.scopeType(), event.scopeId(), event.recipients().size(),
                        failed, denied, firstFailedProgressId);
            }
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            OnboardingReminderNotificationEvent.Recipient recipient,
            NotificationScopeType notifScope,
            Long scopeId,
            Locale locale) {
        return new NotificationDeliveryRequest(
                recipient.userId(),
                "ONBOARDING_REMINDER",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.onboarding.reminder.title", null,
                        "オンボーディングリマインド", locale),
                messageSource.getMessage(
                        "notification.onboarding.reminder.body", null,
                        "未完了のオンボーディングステップがあります。", locale),
                "ONBOARDING",
                recipient.progressId(),
                notifScope,
                scopeId,
                "/onboarding/progress/" + recipient.progressId(),
                null);
    }
}
