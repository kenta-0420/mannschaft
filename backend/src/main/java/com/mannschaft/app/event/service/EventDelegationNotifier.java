package com.mannschaft.app.event.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * イベント代理出席の通知発火（F03.10 §8）。
 *
 * <p>{@link com.mannschaft.app.schedule.service.ScheduleDelegationNotifier} のイベント版。
 * sourceType = "EVENT" で IN_APP 通知を作成し、PUSH 対象は {@link NotificationDispatchService} で配信する。</p>
 */
@Component
@RequiredArgsConstructor
public class EventDelegationNotifier {

    private static final String SOURCE_TYPE = "EVENT";

    private final NotificationService notificationService;
    private final NotificationDispatchService notificationDispatchService;
    private final UserRepository userRepository;

    /** 代理依頼（PENDING）→ 代理人へ IN_APP + PUSH。 */
    public void notifyRequestPending(EventDelegationEntity delegation) {
        String delegatorName = displayName(delegation.getDelegatorId());
        send(delegation.getDelegateId(), delegation,
                "代理出席の依頼",
                delegatorName + "さんから代理出席の依頼が届いています。承認または拒否してください。",
                true);
    }

    /** 自動承認（ACCEPTED）→ 代理人へ IN_APP + PUSH。 */
    public void notifyAutoAccepted(EventDelegationEntity delegation) {
        String delegatorName = displayName(delegation.getDelegatorId());
        send(delegation.getDelegateId(), delegation,
                "代理出席の指定",
                delegatorName + "さんの代理出席に指定されました。",
                true);
    }

    /** 承認（PENDING → ACCEPTED）→ 委任者へ IN_APP。 */
    public void notifyAccepted(EventDelegationEntity delegation) {
        String delegateName = displayName(delegation.getDelegateId());
        send(delegation.getDelegatorId(), delegation,
                "代理出席が承認されました",
                delegateName + "さんが代理出席を承認しました。",
                false);
    }

    /** 拒否（PENDING → REJECTED）→ 委任者へ IN_APP + PUSH。 */
    public void notifyRejected(EventDelegationEntity delegation) {
        String delegateName = displayName(delegation.getDelegateId());
        send(delegation.getDelegatorId(), delegation,
                "代理出席が拒否されました",
                delegateName + "さんが代理出席を拒否しました。別の代理人を指定してください。",
                true);
    }

    /** 取消（→ CANCELLED）→ 代理人へ IN_APP。 */
    public void notifyCancelled(EventDelegationEntity delegation) {
        send(delegation.getDelegateId(), delegation,
                "代理出席が取り消されました",
                "代理出席が取り消されました。",
                false);
    }

    /** 代理人のスコープ退会 → 委任者へ IN_APP + PUSH（§5.8）。 */
    public void notifyDelegateLeft(EventDelegationEntity delegation) {
        String delegateName = displayName(delegation.getDelegateId());
        send(delegation.getDelegatorId(), delegation,
                "代理人が退会しました",
                "代理人に指定していた " + delegateName + " さんがメンバーを退会しました。代理を再設定してください。",
                true);
    }

    /** 委任者のスコープ退会 → 代理人へ IN_APP（§5.8）。 */
    public void notifyDelegatorLeft(EventDelegationEntity delegation) {
        String delegatorName = displayName(delegation.getDelegatorId());
        send(delegation.getDelegateId(), delegation,
                "代理出席が取り消されました",
                delegatorName + " さんが退会したため代理出席は取り消されました。",
                false);
    }

    private void send(Long recipientUserId, EventDelegationEntity delegation,
                      String title, String body, boolean push) {
        NotificationScopeType scopeType = delegation.getOrganizationId() != null
                ? NotificationScopeType.ORGANIZATION : NotificationScopeType.TEAM;
        Long scopeId = delegation.getOrganizationId() != null
                ? delegation.getOrganizationId() : delegation.getTeamId();

        NotificationEntity notification = notificationService.createNotification(
                recipientUserId,
                "EVENT_PROXY_DELEGATION",
                NotificationPriority.NORMAL,
                title, body,
                SOURCE_TYPE, delegation.getEventId(),
                scopeType, scopeId,
                "/events/" + delegation.getEventId(), null);
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
