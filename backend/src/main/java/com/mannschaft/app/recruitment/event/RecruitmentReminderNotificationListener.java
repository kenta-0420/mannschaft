package com.mannschaft.app.recruitment.event;


import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F03.11 募集型予約リマインドの通知配送リスナー（Issue #2834 / CMP-056 第2群ロット2）。
 *
 * <p>{@code RecruitmentReminderRunner#processOne} の独立トランザクションが commit された後
 * （{@code AFTER_COMMIT}）に非同期（{@code event-pool}）で発火する。<b>単一受信者</b>の金型として
 * 第2群ロット1 の {@code QuickMemoReminderNotificationListener} と同型。</p>
 *
 * <h2>業務本文はイベントに載せず読み直す</h2>
 * <p>募集タイトルは業務本文であるためイベントには積まず、{@code listingId} から読み直す。
 * 読み直しに失敗した場合（募集が確定直後に削除された等）は握りつぶさず ERROR ログを残して
 * 配送を中止する。</p>
 *
 * <h2>挙動の同一性</h2>
 * <p>是正前は {@code NotificationHelper#notify}（= {@code createNotification} + {@code dispatch}）を
 * 呼んでいた。{@link NotificationDeliveryRunner#sendOne} も create + dispatch であるため、
 * Push / WebSocket 配信の有無は<b>変わらない</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentReminderNotificationListener {

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final RecruitmentListingRepository listingRepository;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.DROP_WHEN_DISABLED,
            gateKeys = {"FEATURE_RECRUITMENT_ENABLED"},
            reason = "募集は棚卸し台帳で beta=停止・gate_key=FEATURE_RECRUITMENT_ENABLED を持つ隔離対象であり、"
                    + "機能停止中に開催24時間前のリマインドだけが利用者へ飛ぶことを避ける。ドロップした"
                    + "イベントは再生されず通知は失われるが、recruitment_reminders.sent_at はバッチ側で"
                    + "既に確定済みであり、募集・参加状況は募集詳細画面で確認できるため整合性は壊れない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecruitmentReminderNotification(RecruitmentReminderNotificationEvent event) {
        if (event.recipientUserId() == null || event.listingId() == null) {
            return;
        }

        // 業務本文（募集タイトル・スコープ）の読み直し。失敗は握りつぶさず配送を中止する。
        RecruitmentListingEntity listing;
        try {
            listing = listingRepository.findById(event.listingId()).orElse(null);
        } catch (Exception e) {
            log.error("募集リマインド通知の募集読み直しに失敗しました（配送中止）: "
                            + "reminderId={}, listingId={}, recipientUserId={}",
                    event.reminderId(), event.listingId(), event.recipientUserId(), e);
            return;
        }
        if (listing == null) {
            log.error("募集リマインド通知の募集が読み直し時点で存在しません（配送中止）: "
                            + "reminderId={}, listingId={}, recipientUserId={}",
                    event.reminderId(), event.listingId(), event.recipientUserId());
            return;
        }

        // 単一受信者のため locale 解決も配送も同じ try に入れてよい（巻き添えになる他受信者がいない）。
        try {
            Locale locale = resolveLocale(event.recipientUserId());
            NotificationDeliveryRequest request = buildRequest(event, listing, locale);
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                // deny のみのときは WARN に留め、ERROR と混ぜない。
                log.warn("募集リマインド通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, notificationType={}, sourceType={}, sourceId={}, reminderId={}",
                        request.recipientUserId(), request.notificationType(),
                        request.sourceType(), request.sourceId(), event.reminderId());
            }
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は TX 外なので rollback で消えない。
            log.error("募集リマインド通知の配送に失敗しました: recipientUserId={}, reminderId={}, listingId={}",
                    event.recipientUserId(), event.reminderId(), event.listingId(), e);
        }
    }

    /** 受信者の locale を解決する（解決自体の失敗は既定 locale で継続する）。 */
    private Locale resolveLocale(Long userId) {
        try {
            return Locale.forLanguageTag(userLocaleCache.getLocale(userId));
        } catch (Exception e) {
            log.warn("募集リマインドの locale 解決に失敗（既定 locale で継続）: recipientUserId={}, error={}",
                    userId, e.getMessage());
            return Locale.JAPANESE;
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            RecruitmentReminderNotificationEvent event,
            RecruitmentListingEntity listing,
            Locale locale) {
        NotificationScopeType scopeType = listing.getScopeType() == RecruitmentScopeType.TEAM
                ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
        return new NotificationDeliveryRequest(
                event.recipientUserId(),
                "RECRUITMENT_REMINDER",
                NotificationPriority.NORMAL,
                messageSource.getMessage(
                        "notification.recruitment.reminder.title", new Object[]{listing.getTitle()},
                        "リマインド: " + listing.getTitle(), locale),
                messageSource.getMessage(
                        "notification.recruitment.reminder.body", new Object[]{listing.getTitle()},
                        listing.getTitle() + " が24時間後に開催されます。", locale),
                "RECRUITMENT_LISTING",
                listing.getId(),
                scopeType,
                listing.getScopeId(),
                "/recruitment-listings/" + listing.getId(),
                null);
    }
}
