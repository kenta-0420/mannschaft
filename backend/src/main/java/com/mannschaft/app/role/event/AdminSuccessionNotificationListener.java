package com.mannschaft.app.role.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 柱①「ADMINゼロ根治」— 強制承継の通知配送リスナー
 * （通知のトランザクション境界番人 Issue #2834 / CMP-056 / #2990 対応）。
 *
 * <p>{@code RoleSuccessionService} の業務トランザクションが commit された後（{@code AFTER_COMMIT}）に
 * 非同期（{@code event-pool}）で発火する。以前は {@code RoleSuccessionService#forceTransferForPurge} /
 * {@code #promoteForBatchSuccession} の {@code @Transactional} メソッド内から
 * {@code NotificationHelper#notify} を直接呼んでいたが、
 * {@code NotificationTransactionBoundaryGuardTest}（原則5: 付随通知は業務TX内ではイベント発行のみ）
 * の新規違反となるため、本リスナーへ切り出した（金型: {@code ContactRequestNotificationListener}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSuccessionNotificationListener {

    private static final String NOTIF_ADMIN_SUCCESSION_FORCED = "ADMIN_SUCCESSION_FORCED";
    private static final String NOTIF_SOURCE_USER = "USER";
    private static final String SCOPE_TEAM = "TEAM";

    private final NotificationHelper notificationHelper;

    /**
     * 昇格された利用者へ「管理者に自動指名されました」を通知する。
     *
     * <p>文面は発生経路（purge / 夜次バッチ）で本文のみ出し分ける（従来の直接呼び出し時と同内容）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "柱①ADMINゼロ根治の強制承継完了通知。止めても昇格自体（ADMIN行の更新）は既に完了済みで"
                    + "業務影響は無いが、被昇格者への周知が届かなくなる。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAdminSuccessionForced(AdminSuccessionForcedNotificationEvent event) {
        boolean team = SCOPE_TEAM.equals(event.scopeType());
        String body = event.reason() == AdminSuccessionForcedNotificationEvent.Reason.PURGE
                ? "先任の管理者の退会に伴い、あなたが管理者に自動で指名されました。"
                : "管理者不在の状態が検出されたため、あなたが管理者に自動で指名されました。";
        try {
            notificationHelper.notify(
                    event.candidateId(),
                    NOTIF_ADMIN_SUCCESSION_FORCED,
                    NotificationPriority.HIGH,
                    "管理者に自動指名されました",
                    body,
                    NOTIF_SOURCE_USER,
                    null,
                    team ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION,
                    event.scopeId(),
                    null,
                    event.withdrawingUserId());
        } catch (Exception e) {
            log.error("強制承継通知の配送に失敗しました: scopeType={}, scopeId={}, candidateId={}, reason={}",
                    event.scopeType(), event.scopeId(), event.candidateId(), event.reason(), e);
        }
    }
}
