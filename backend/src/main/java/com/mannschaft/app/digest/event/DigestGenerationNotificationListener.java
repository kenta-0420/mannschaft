package com.mannschaft.app.digest.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * AI ダイジェスト生成の完了／失敗通知の配送リスナー（Issue #2997 / CMP-260827-1152 G7-a）。
 *
 * <p>{@code DigestGenerationRunner} の確定TX（GENERATED／FAILED）が commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火し、作成者（{@code triggeredBy}）へ {@code NotificationDeliveryRunner#sendOne}
 * （{@code REQUIRES_NEW}）で配送する。金型: {@code ShiftPreferenceReminderNotificationListener}。</p>
 *
 * <h2>配送意味論（是正前と同一）</h2>
 * <ul>
 *   <li>通知種別 {@code DIGEST_COMPLETED}（NORMAL）／{@code DIGEST_FAILED}（HIGH）、文面・locale（受信者の言語）・
 *       遷移先 {@code /digests/{id}}・スコープ（TEAM／それ以外は ORGANIZATION）は同一。</li>
 *   <li>可視性判定は {@code sendOne} に任せる。deny は例外ではなく {@code VISIBILITY_DENIED} として返り、WARN で記録して終える。</li>
 *   <li>{@code triggeredBy} が null のときは配送しない（是正前は NPE で生成成功が FAILED に落ちた）。</li>
 * </ul>
 *
 * <p><b>既知の限界</b>: {@code event-pool} が飽和して投入が拒否された場合（{@code AbortPolicy}）、その回の通知は失われる
 * （ダイジェスト自体は確定済みで、利用者は一覧画面で結果を確認できる）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestGenerationNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "AIダイジェスト生成は利用者が明示的に要求した操作の結果通知であり、"
                    + "止めると生成の成否が利用者に伝わらなくなるため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDigestGenerationNotification(DigestGenerationNotificationEvent event) {
        if (event.triggeredBy() == null) {
            log.info("起動者不明のため AI ダイジェスト通知を配送しません: digestId={}, kind={}",
                    event.digestId(), event.kind());
            return;
        }
        try {
            NotificationDeliveryRequest request = buildRequest(event);
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                log.warn("AI ダイジェスト通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(), request.sourceId());
            }
        } catch (Exception e) {
            // 確定済みのダイジェストには影響しない。握りつぶさず ERROR で観測可能にする。
            log.error("AI ダイジェスト通知の配送に失敗しました: digestId={}, kind={}, recipientUserId={}",
                    event.digestId(), event.kind(), event.triggeredBy(), e);
        }
    }

    private NotificationDeliveryRequest buildRequest(DigestGenerationNotificationEvent event) {
        NotificationScopeType notifScope = "TEAM".equals(event.scopeType())
                ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
        Locale locale = Locale.forLanguageTag(userLocaleCache.getLocale(event.triggeredBy()));
        boolean completed = event.kind() == DigestGenerationNotificationEvent.Kind.COMPLETED;
        return new NotificationDeliveryRequest(
                event.triggeredBy(),
                completed ? "DIGEST_COMPLETED" : "DIGEST_FAILED",
                completed ? NotificationPriority.NORMAL : NotificationPriority.HIGH,
                completed
                        ? messageSource.getMessage("notification.digest.completed.title", null,
                                "ダイジェスト生成完了", locale)
                        : messageSource.getMessage("notification.digest.failed.title", null,
                                "ダイジェスト生成失敗", locale),
                completed
                        ? messageSource.getMessage("notification.digest.completed.body", null,
                                "AIダイジェストの生成が完了しました。", locale)
                        : messageSource.getMessage("notification.digest.failed.body", null,
                                "AIダイジェストの生成に失敗しました。再試行してください。", locale),
                "DIGEST",
                event.digestId(),
                notifScope,
                event.scopeId(),
                "/digests/" + event.digestId(),
                null);
    }
}
