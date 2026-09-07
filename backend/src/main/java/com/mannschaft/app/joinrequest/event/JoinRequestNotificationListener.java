package com.mannschaft.app.joinrequest.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 柱③-A「MEMBER 参加申請（join request）」の通知配送リスナー（CMP-260901-1538）。
 *
 * <p>{@code JoinRequestService} の業務トランザクションが commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。業務 TX 内では直接 {@code NotificationHelper} を呼ばず
 * イベント発行のみに留める（通知のトランザクション境界番人対応・金型:
 * {@code AdminSuccessionNotificationListener}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JoinRequestNotificationListener {

    private static final String NOTIF_JOIN_REQUEST_RECEIVED = "JOIN_REQUEST_RECEIVED";
    private static final String NOTIF_JOIN_REQUEST_APPROVED = "JOIN_REQUEST_APPROVED";
    private static final String NOTIF_JOIN_REQUEST_REJECTED = "JOIN_REQUEST_REJECTED";
    private static final String NOTIF_SOURCE_USER = "USER";

    private final NotificationHelper notificationHelper;
    private final RoleService roleService;

    /**
     * 参加申請受理を対象スコープの ADMIN/DEPUTY_ADMIN 全員へ通知する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "柱③-A 参加申請の受理通知。止めても申請自体（DB行）は既に作成済みで業務影響は無いが、"
                    + "ADMIN が申請の存在に気づけなくなる。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJoinRequestCreated(JoinRequestCreatedEvent event) {
        try {
            boolean team = "TEAM".equals(event.scopeType());
            List<Long> adminUserIds = team
                    ? roleService.getAdminUserIdsByTeamId(event.scopeId())
                    : roleService.getAdminUserIdsByOrganizationId(event.scopeId());
            String title = "参加申請が届きました";
            String body = (event.scopeName() != null ? event.scopeName() : "") + " へのメンバー参加申請が届きました。";
            NotificationScopeType scopeType = team ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
            for (Long adminUserId : adminUserIds) {
                notificationHelper.notify(
                        adminUserId, NOTIF_JOIN_REQUEST_RECEIVED, title, body,
                        NOTIF_SOURCE_USER, null, scopeType, event.scopeId(),
                        null, event.requesterUserId());
            }
        } catch (Exception e) {
            log.error("参加申請受理通知の配送に失敗しました: requestId={}, scopeType={}, scopeId={}",
                    event.requestId(), event.scopeType(), event.scopeId(), e);
        }
    }

    /**
     * 審査結果（承認/却下）を申請者本人へ通知する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "柱③-A 参加申請の審査結果通知。止めても承認/却下自体（membership 付与含む）は既に"
                    + "完了済みで業務影響は無いが、申請者が結果に気づけなくなる。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJoinRequestReviewed(JoinRequestReviewedEvent event) {
        try {
            boolean team = "TEAM".equals(event.scopeType());
            String scopeName = event.scopeName() != null ? event.scopeName() : "";
            String title = event.approved() ? "参加申請が承認されました" : "参加申請が却下されました";
            String body = event.approved()
                    ? scopeName + " への参加申請が承認されました。"
                    : scopeName + " への参加申請が却下されました。";
            notificationHelper.notify(
                    event.requesterUserId(),
                    event.approved() ? NOTIF_JOIN_REQUEST_APPROVED : NOTIF_JOIN_REQUEST_REJECTED,
                    title, body, NOTIF_SOURCE_USER, null,
                    team ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION,
                    event.scopeId(), null, null);
        } catch (Exception e) {
            log.error("参加申請審査結果通知の配送に失敗しました: requestId={}, scopeType={}, scopeId={}, approved={}",
                    event.requestId(), event.scopeType(), event.scopeId(), event.approved(), e);
        }
    }
}
