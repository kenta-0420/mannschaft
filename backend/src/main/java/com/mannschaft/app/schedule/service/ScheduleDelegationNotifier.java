package com.mannschaft.app.schedule.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * スケジュール代理出席の通知発火（F03.10 §8）。
 *
 * <p>6 トリガーのうちスケジュール側で発生する 5 種（依頼 PENDING / 自動 ACCEPTED / 承認 / 拒否 / 取消）と
 * 退会連動の通知を担当する。{@link NotificationService#createNotification} で IN_APP 通知を作成し、
 * {@link NotificationDispatchService#dispatch} で PUSH を配信する（既存 RSVP/点呼と同パターン）。</p>
 *
 * <p>通知の発火は呼び出し元の {@code @Transactional} 内で同期実行する。IN_APP は同一トランザクションで
 * 確定させ、PUSH の配信失敗が業務トランザクションを巻き戻さないよう {@link NotificationDispatchService}
 * 側のフォールバックに委ねる（RSVP サービスと同じ作法）。</p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleDelegationNotifier {

    private static final String SOURCE_TYPE = "SCHEDULE";

    private final NotificationService notificationService;
    private final NotificationDispatchService notificationDispatchService;
    private final UserRepository userRepository;

    /** 代理依頼（PENDING）→ 代理人へ IN_APP + PUSH。 */
    public void notifyRequestPending(ScheduleDelegationEntity delegation) {
        String delegatorName = displayName(delegation.getDelegatorId());
        send(delegation.getDelegateId(), delegation,
                "代理出席の依頼",
                delegatorName + "さんから代理出席の依頼が届いています。承認または拒否してください。",
                true);
    }

    /** 自動承認（ACCEPTED）→ 代理人へ IN_APP + PUSH。 */
    public void notifyAutoAccepted(ScheduleDelegationEntity delegation) {
        String delegatorName = displayName(delegation.getDelegatorId());
        send(delegation.getDelegateId(), delegation,
                "代理出席の指定",
                delegatorName + "さんの代理出席に指定されました。",
                true);
    }

    /** 承認（PENDING → ACCEPTED）→ 委任者へ IN_APP。 */
    public void notifyAccepted(ScheduleDelegationEntity delegation) {
        String delegateName = displayName(delegation.getDelegateId());
        send(delegation.getDelegatorId(), delegation,
                "代理出席が承認されました",
                delegateName + "さんが代理出席を承認しました。",
                false);
    }

    /** 拒否（PENDING → REJECTED）→ 委任者へ IN_APP + PUSH。 */
    public void notifyRejected(ScheduleDelegationEntity delegation) {
        String delegateName = displayName(delegation.getDelegateId());
        send(delegation.getDelegatorId(), delegation,
                "代理出席が拒否されました",
                delegateName + "さんが代理出席を拒否しました。別の代理人を指定してください。",
                true);
    }

    /** 取消（→ CANCELLED）→ 代理人へ IN_APP。 */
    public void notifyCancelled(ScheduleDelegationEntity delegation) {
        send(delegation.getDelegateId(), delegation,
                "代理出席が取り消されました",
                "代理出席が取り消されました。",
                false);
    }

    /** 代理人のスコープ退会 → 委任者へ IN_APP + PUSH（§5.8）。 */
    public void notifyDelegateLeft(ScheduleDelegationEntity delegation) {
        String delegateName = displayName(delegation.getDelegateId());
        send(delegation.getDelegatorId(), delegation,
                "代理人が退会しました",
                "代理人に指定していた " + delegateName + " さんがメンバーを退会しました。代理を再設定してください。",
                true);
    }

    /** 委任者のスコープ退会 → 代理人へ IN_APP（§5.8）。 */
    public void notifyDelegatorLeft(ScheduleDelegationEntity delegation) {
        String delegatorName = displayName(delegation.getDelegatorId());
        send(delegation.getDelegateId(), delegation,
                "代理出席が取り消されました",
                delegatorName + " さんが退会したため代理出席は取り消されました。",
                false);
    }

    private void send(Long recipientUserId, ScheduleDelegationEntity delegation,
                      String title, String body, boolean push) {
        NotificationScopeType scopeType = delegation.getOrganizationId() != null
                ? NotificationScopeType.ORGANIZATION : NotificationScopeType.TEAM;
        Long scopeId = delegation.getOrganizationId() != null
                ? delegation.getOrganizationId() : delegation.getTeamId();

        NotificationEntity notification = notificationService.createNotification(
                recipientUserId,
                "SCHEDULE_PROXY_DELEGATION",
                NotificationPriority.NORMAL,
                title, body,
                SOURCE_TYPE, delegation.getScheduleId(),
                scopeType, scopeId,
                "/schedules/" + delegation.getScheduleId(), null);
        if (push && notification != null) {
            notificationDispatchService.dispatch(notification);
        }
    }

    private String displayName(Long userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse("");
    }
}
