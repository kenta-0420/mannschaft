package com.mannschaft.app.memberinfo.event;


import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F14.2 メンバー情報更新リマインドの通知配送リスナー（Issue #2834 / CMP-056 第2群ロット2）。
 *
 * <p>{@code MemberInfoUpdateReminderRunner#markReminderSent} の独立トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>単一受信者</b>の金型として
 * 第2群ロット1 の {@code QuickMemoReminderNotificationListener} と同型。</p>
 *
 * <h2>業務本文はイベントに載せず読み直す</h2>
 * <p>通知本文に埋めるフィールド名は利用者が定義した業務データであるためイベントには積まず、
 * {@code fieldId} から読み直す。読み直しに失敗した場合は握りつぶさず ERROR ログを残して配送を中止する。</p>
 *
 * <h2>挙動の同一性</h2>
 * <p>是正前は {@code NotificationHelper#notify}（= {@code createNotification} + {@code dispatch}）を
 * 呼んでいた。{@link NotificationDeliveryRunner#sendOne} も create + dispatch であるため、
 * Push / WebSocket 配信の有無は<b>変わらない</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberInfoUpdateReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final TeamMemberInfoFieldRepository fieldRepository;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "メンバー情報管理は棚卸し台帳に独立した gate_key を持たないチーム運営の常時提供機能であり、"
                    + "更新リマインド通知だけを止める停止条件が存在しないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberInfoUpdateReminderNotification(MemberInfoUpdateReminderNotificationEvent event) {
        if (event.recipientUserId() == null || event.teamId() == null || event.fieldId() == null) {
            return;
        }

        // 業務本文（フィールド名）の読み直し。失敗は握りつぶさず配送を中止する。
        TeamMemberInfoFieldEntity field;
        try {
            field = fieldRepository.findById(event.fieldId()).orElse(null);
        } catch (Exception e) {
            log.error("メンバー情報更新リマインドのフィールド読み直しに失敗しました（配送中止）: "
                            + "teamId={}, recipientUserId={}, fieldId={}",
                    event.teamId(), event.recipientUserId(), event.fieldId(), e);
            return;
        }
        if (field == null) {
            log.error("メンバー情報更新リマインドのフィールドが読み直し時点で存在しません（配送中止）: "
                            + "teamId={}, recipientUserId={}, fieldId={}",
                    event.teamId(), event.recipientUserId(), event.fieldId());
            return;
        }

        // 単一受信者のため locale 解決も配送も同じ try に入れてよい（巻き添えになる他受信者がいない）。
        try {
            Locale locale = resolveLocale(event.recipientUserId());
            NotificationDeliveryRequest request = buildRequest(event, field.getFieldName(), locale);
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                // deny のみのときは WARN に留め、ERROR と混ぜない。
                log.warn("メンバー情報更新リマインド通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId());
            }
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は TX 外なので rollback で消えない。
            log.error("メンバー情報更新リマインド通知の配送に失敗しました: recipientUserId={}, teamId={}, fieldId={}",
                    event.recipientUserId(), event.teamId(), event.fieldId(), e);
        }
    }

    /** 受信者の locale を解決する（解決自体の失敗は既定 locale で継続する）。 */
    private Locale resolveLocale(Long userId) {
        try {
            return Locale.forLanguageTag(userLocaleCache.getLocale(userId));
        } catch (Exception e) {
            log.warn("メンバー情報更新リマインドの locale 解決に失敗（既定 locale で継続）: recipientUserId={}, error={}",
                    userId, e.getMessage());
            return Locale.JAPANESE;
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            MemberInfoUpdateReminderNotificationEvent event, String fieldName, Locale locale) {
        return new NotificationDeliveryRequest(
                event.recipientUserId(),
                "MEMBER_INFO_UPDATE_REMINDER",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.memberinfo.updateReminder.title", null,
                        "情報の更新をお願いします", locale),
                messageSource.getMessage(
                        "notification.memberinfo.updateReminder.body", new Object[]{fieldName},
                        "「" + fieldName + "」等の情報を更新してください。", locale),
                "TEAM_MEMBER_INFO",
                event.teamId(),
                NotificationScopeType.TEAM,
                event.teamId(),
                "/teams/" + event.teamId() + "/member-info",
                null);
    }
}
