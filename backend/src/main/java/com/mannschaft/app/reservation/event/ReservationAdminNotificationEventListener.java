package com.mannschaft.app.reservation.event;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 予約関連イベントに対する管理者通知リスナー（F10.7 業務アラート用）。
 *
 * <p>予約作成・メンバーキャンセル時に ADMIN / MANAGE_RESERVATIONS 権限保有 DEPUTY_ADMIN へ
 * 非同期で通知を送信する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationAdminNotificationEventListener {

    private final UserRoleRepository userRoleRepository;
    private final NotificationService notificationService;

    /**
     * 予約作成イベントを受信し、管理者へ通知する。
     *
     * @param event 予約作成イベント
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCreated(ReservationCreatedEvent event) {
        List<Long> recipientIds = resolveReservationRecipients(event.getTeamId());
        String notificationType;
        String title;
        String body;

        if (event.getApprovalMode() == ApprovalMode.MANUAL) {
            notificationType = "RESERVATION_PENDING_APPROVAL";
            title = "承認待ての予約があります";
            body = event.getSlotTitle() + "への予約申込があります（" + event.getBookedAtFormatted() + "）";
        } else {
            notificationType = "RESERVATION_RECEIVED";
            title = "予約が入りました";
            body = event.getSlotTitle() + "が予約されました（" + event.getBookedAtFormatted() + "）";
        }

        for (Long recipientId : recipientIds) {
            if (recipientId.equals(event.getActorUserId())) {
                continue; // 自己通知スキップ
            }
            try {
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
                        "/teams/" + event.getTeamId() + "/reservations",
                        event.getActorUserId()
                );
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
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReservationCancelledByMember(ReservationCancelledByMemberEvent event) {
        List<Long> recipientIds = resolveReservationRecipients(event.getTeamId());

        for (Long recipientId : recipientIds) {
            if (recipientId.equals(event.getActorUserId())) {
                continue;
            }
            try {
                notificationService.createNotification(
                        recipientId,
                        "RESERVATION_CANCELLED_BY_MEMBER",
                        NotificationPriority.NORMAL,
                        "予約がキャンセルされました",
                        event.getSlotTitle() + "の予約がキャンセルされました",
                        "RESERVATION",
                        event.getReservationId(),
                        NotificationScopeType.TEAM,
                        event.getTeamId(),
                        "/teams/" + event.getTeamId() + "/reservations",
                        event.getActorUserId()
                );
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
}
