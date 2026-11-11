package com.mannschaft.app.reservation.event;

import com.mannschaft.app.admin.service.AdminBusinessAlertService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.service.TeamService;
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

/**
 * 予約関連イベントに対する管理者通知リスナー（F10.7 業務アラート用）。
 *
 * <p>予約作成・メンバーキャンセル時に ADMIN / MANAGE_RESERVATIONS 権限保有 DEPUTY_ADMIN へ
 * 非同期で通知を送信する。</p>
 *
 * <p>遷移先は {@code /teams/{slug}/reservations}（CMP-261030-0648。FE のルートは slug しか受けない）。
 * slug は宛先ループの前に1回だけ解決し、宛先が0人なら解決しない。slug が null（削除済み・存在しない）
 * または取得が例外のときは数値 URL を作らず {@code /notifications} にして、通知そのものは送る。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationAdminNotificationEventListener {

    private final UserRoleRepository userRoleRepository;
    private final UserLocaleCache userLocaleCache;
    private final NotificationService notificationService;
    private final AdminBusinessAlertService adminBusinessAlertService;
    private final MessageSource messageSource;
    /** 遷移先 URL の slug 解決用（Repository は直接使わず Service 経由）。 */
    private final TeamService teamService;

    /**
     * 予約作成イベントを受信し、管理者へ通知する。
     *
     * @param event 予約作成イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予約の作成・キャンセルに伴う管理者通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCreated(ReservationCreatedEvent event) {
        List<Long> recipientIds = excludeActor(
                resolveReservationRecipients(event.getTeamId()), event.getActorUserId());
        if (recipientIds.isEmpty()) {
            return;
        }
        String actionUrl = buildReservationsUrl(event.getTeamId(), event.getReservationId());

        boolean pendingApproval = event.getApprovalMode() == ApprovalMode.MANUAL;
        String notificationType = pendingApproval ? "RESERVATION_PENDING_APPROVAL" : "RESERVATION_RECEIVED";
        String titleKey = pendingApproval
                ? "notification.reservation.admin.pendingApproval.title"
                : "notification.reservation.admin.received.title";
        String bodyKey = pendingApproval
                ? "notification.reservation.admin.pendingApproval.body"
                : "notification.reservation.admin.received.body";
        String defaultTitle = pendingApproval ? "承認待ての予約があります" : "予約が入りました";

        for (Long recipientId : recipientIds) {
            try {
                Locale locale = resolveLocale(recipientId);
                String title = messageSource.getMessage(titleKey, null, defaultTitle, locale);
                String body = messageSource.getMessage(
                        bodyKey,
                        new Object[]{event.getSlotTitle(), event.getBookedAtFormatted()},
                        event.getSlotTitle() + (pendingApproval ? "への予約申込があります（" : "が予約されました（")
                                + event.getBookedAtFormatted() + "）",
                        locale);
                notificationService.createNotification(
                        recipientId,
                        notificationType,
                        NotificationPriority.HIGH,
                        title,
                        body,
                        "RESERVATION",
                        event.getReservationId(),
                        NotificationScopeType.TEAM,
                        event.getTeamId(),
                        actionUrl,
                        event.getActorUserId()
                );
                adminBusinessAlertService.invalidateCache(recipientId);
            } catch (Exception e) {
                log.warn("予約通知の送信に失敗しました: recipientId={}, reservationId={}",
                        recipientId, event.getReservationId(), e);
            }
        }
    }

    /**
     * メンバーによる予約キャンセルイベントを受信し、管理者へ通知する。
     *
     * @param event 予約キャンセルイベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予約の作成・キャンセルに伴う管理者通知。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCancelledByMember(ReservationCancelledByMemberEvent event) {
        List<Long> recipientIds = excludeActor(
                resolveReservationRecipients(event.getTeamId()), event.getActorUserId());
        if (recipientIds.isEmpty()) {
            return;
        }
        String actionUrl = buildReservationsUrl(event.getTeamId(), event.getReservationId());

        for (Long recipientId : recipientIds) {
            try {
                Locale locale = resolveLocale(recipientId);
                String title = messageSource.getMessage(
                        "notification.reservation.admin.cancelledByMember.title", null,
                        "予約がキャンセルされました", locale);
                String body = messageSource.getMessage(
                        "notification.reservation.admin.cancelledByMember.body",
                        new Object[]{event.getSlotTitle()},
                        event.getSlotTitle() + "の予約がキャンセルされました",
                        locale);
                notificationService.createNotification(
                        recipientId,
                        "RESERVATION_CANCELLED_BY_MEMBER",
                        NotificationPriority.NORMAL,
                        title,
                        body,
                        "RESERVATION",
                        event.getReservationId(),
                        NotificationScopeType.TEAM,
                        event.getTeamId(),
                        actionUrl,
                        event.getActorUserId()
                );
                adminBusinessAlertService.invalidateCache(recipientId);
            } catch (Exception e) {
                log.warn("予約キャンセル通知の送信に失敗しました: recipientId={}, reservationId={}",
                        recipientId, event.getReservationId(), e);
            }
        }
    }

    /**
     * 予約通知の受信者一覧を解決する。
     *
     * <p>ADMIN 全員 + MANAGE_RESERVATIONS 権限を持つ DEPUTY_ADMIN を OR 集約して返す。</p>
     *
     * @param teamId チームID
     * @return 重複排除済みの受信者ユーザーIDリスト
     */
    private List<Long> resolveReservationRecipients(Long teamId) {
        List<Long> recipients = new ArrayList<>();
        recipients.addAll(userRoleRepository.findAdminUserIdsByTeamId(teamId));
        recipients.addAll(userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(teamId, "MANAGE_RESERVATIONS"));
        return recipients.stream().distinct().toList();
    }

    /** 自己通知を除いた宛先（操作した本人には送らない）。 */
    private static List<Long> excludeActor(List<Long> recipientIds, Long actorUserId) {
        return recipientIds.stream().filter(id -> !id.equals(actorUserId)).toList();
    }

    /**
     * チームの予約画面への遷移先を返す（宛先ループの前に1回だけ呼ぶ）。
     *
     * <p>slug が解決できなければ数値 URL は作らず通知一覧へ逃がす。取得の例外も通知を止めない
     * （遷移先が粗くなるだけで、宛先集合は変えない）。</p>
     */
    private String buildReservationsUrl(Long teamId, Long reservationId) {
        String slug;
        try {
            slug = teamService.getSlugById(teamId);
        } catch (Exception e) {
            log.warn("予約通知の遷移先 slug を解決できません（/notifications で送ります）: teamId={}, reservationId={}",
                    teamId, reservationId, e);
            slug = null;
        }
        return slug != null ? "/teams/" + slug + "/reservations" : "/notifications";
    }

    /**
     * 受信者ユーザーの locale を解決する（{@link UserLocaleCache} 経由。D-5: 予約ドメインから
     * auth ドメインのリポジトリへ直接依存しない・{@code common.i18n} 配下の共有サービス経由に限定する）。
     */
    private Locale resolveLocale(Long userId) {
        return Locale.forLanguageTag(userLocaleCache.getLocale(userId));
    }
}
