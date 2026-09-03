package com.mannschaft.app.schedule.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.event.ScheduleDelegationNotificationEvent;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * スケジュール代理出席の通知配送リスナー（F03.10 §8 / Issue #2990 L2 ROLLBACK_COUPLED 是正）。
 *
 * <h2>是正前の欠陥 — 何が巻き戻っていたか</h2>
 * <p>是正前の本クラスは素の {@code @Component} で、{@link ScheduleDelegationService} の
 * {@code @Transactional} メソッドから<b>同期的に呼ばれていた</b>。内部の
 * {@code NotificationService#createNotification} は既定の {@code REQUIRED} 伝播で
 * 呼び出し元の業務トランザクションにそのまま参加するため、通知側の DB 例外は rollback-only を残し、
 * <b>代理の指定・承認・拒否・取消という業務処理ごと巻き戻っていた</b>。クラス javadoc には
 * 「PUSH の配信失敗が業務トランザクションを巻き戻さないよう {@code NotificationDispatchService}
 * 側のフォールバックに委ねる」と書かれていたが、これは <b>IN_APP（createNotification）側の
 * 巻き戻しを見落とした誤った説明</b>だった。</p>
 *
 * <p>この欠陥が特に効くのは、業務側から見て「通知はついで」でしかない次の 2 経路である。</p>
 * <ul>
 *   <li>{@code ProxyDelegationCleanupBatchService#cleanupScheduleDelegations}（日次バッチ）—
 *       {@code cancelOnMemberLeft} 経由。1 件の通知失敗で、その回に処理した
 *       <b>孤立委任の CANCELLED 化が全件巻き戻る</b>。</li>
 *   <li>{@code ScheduleAttendanceService#respondAttendance}（出欠回答）—
 *       {@code onDelegatorAttendanceChanged} 経由。通知失敗で
 *       <b>利用者本人の出欠回答そのものが失われる</b>。</li>
 * </ul>
 *
 * <h2>是正後</h2>
 * <p>{@link ScheduleDelegationService} は {@link ScheduleDelegationNotificationEvent} を
 * publish するだけに留め、本クラスが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で
 * 受け取って {@link NotificationDeliveryRunner#sendOne}（1 件 = 1 独立トランザクション）へ委譲する。
 * 通知は 1 通知 1 受信者であるため、受信者ごとの隔離はイベント 1 件ごとの隔離と一致する。</p>
 *
 * <h2>意図的な挙動変更: 3 種の通知に Push/WebSocket 配信が付く</h2>
 * <p>是正前は種別ごとに {@code dispatch} の有無を分けており、承認 / 取消 / 委任者退会の 3 種は
 * <b>IN_APP のみ</b>（Push・WebSocket なし）だった。{@link NotificationDeliveryRunner#sendOne} は
 * create + dispatch であるため、以後この 3 種も Push / WebSocket で配信される。
 * 型（CMP-056 の配送リスナー金型）に寄せた結果として<b>意図的に受け入れた挙動変更</b>であり、
 * 退行ではない（金型 {@code ContactInviteUsedNotificationListener} と同じ判断）。
 * いずれも「自分の代理関係の状態が変わった」という即時性のある事実であり、
 * 既に Push 済みの依頼・拒否・代理人退会と非対称にしておく理由が無い。</p>
 *
 * <h2>業務本文はイベントに載せず読み直す</h2>
 * <p>委任者名・代理人名・スケジュール ID・スコープはいずれも業務データであるため、
 * {@code delegationId} から読み直す。読み直しに失敗した場合は握りつぶさず ERROR ログを残して
 * 配送を中止する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleDelegationNotifier {

    private static final String SOURCE_TYPE = "SCHEDULE";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final ScheduleDelegationRepository delegationRepository;
    private final UserRepository userRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "代理出席はスケジュール（CORE）の一部であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと代理人は自分に代理依頼が来たこと・委任者は代理が拒否されたことを知らぬまま"
                    + "予定当日を迎え、出欠の実態と記録が食い違う。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleDelegationNotification(ScheduleDelegationNotificationEvent event) {
        if (event.delegationId() == null || event.kind() == null) {
            return;
        }

        // 業務本文（委任・スケジュール・スコープ）の読み直し。失敗は握りつぶさず配送を中止する。
        ScheduleDelegationEntity delegation;
        try {
            delegation = delegationRepository.findById(event.delegationId()).orElse(null);
        } catch (Exception e) {
            log.error("代理出席通知の委任読み直しに失敗しました（配送中止）: delegationId={}, kind={}",
                    event.delegationId(), event.kind(), e);
            return;
        }
        if (delegation == null) {
            log.error("代理出席通知の委任が読み直し時点で存在しません（配送中止）: delegationId={}, kind={}",
                    event.delegationId(), event.kind());
            return;
        }

        try {
            NotificationDeliveryRequest request = buildRequest(delegation, event.kind());
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                log.warn("代理出席通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, delegationId={}, kind={}",
                        request.recipientUserId(), event.delegationId(), event.kind());
            }
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TX外なので rollback で消えない。
            log.error("代理出席通知の配送に失敗しました: delegationId={}, kind={}",
                    event.delegationId(), event.kind(), e);
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            ScheduleDelegationEntity delegation, ScheduleDelegationNotificationEvent.Kind kind) {
        Long recipientUserId = switch (kind) {
            // 代理人へ届ける通知
            case REQUEST_PENDING, AUTO_ACCEPTED, CANCELLED, DELEGATOR_LEFT -> delegation.getDelegateId();
            // 委任者へ届ける通知
            case ACCEPTED, REJECTED, DELEGATE_LEFT -> delegation.getDelegatorId();
        };
        String title = switch (kind) {
            case REQUEST_PENDING -> "代理出席の依頼";
            case AUTO_ACCEPTED -> "代理出席の指定";
            case ACCEPTED -> "代理出席が承認されました";
            case REJECTED -> "代理出席が拒否されました";
            case CANCELLED, DELEGATOR_LEFT -> "代理出席が取り消されました";
            case DELEGATE_LEFT -> "代理人が退会しました";
        };
        String body = switch (kind) {
            case REQUEST_PENDING -> displayName(delegation.getDelegatorId())
                    + "さんから代理出席の依頼が届いています。承認または拒否してください。";
            case AUTO_ACCEPTED -> displayName(delegation.getDelegatorId())
                    + "さんの代理出席に指定されました。";
            case ACCEPTED -> displayName(delegation.getDelegateId())
                    + "さんが代理出席を承認しました。";
            case REJECTED -> displayName(delegation.getDelegateId())
                    + "さんが代理出席を拒否しました。別の代理人を指定してください。";
            case CANCELLED -> "代理出席が取り消されました。";
            case DELEGATE_LEFT -> "代理人に指定していた " + displayName(delegation.getDelegateId())
                    + " さんがメンバーを退会しました。代理を再設定してください。";
            case DELEGATOR_LEFT -> displayName(delegation.getDelegatorId())
                    + " さんが退会したため代理出席は取り消されました。";
        };

        NotificationScopeType scopeType = delegation.getOrganizationId() != null
                ? NotificationScopeType.ORGANIZATION : NotificationScopeType.TEAM;
        Long scopeId = delegation.getOrganizationId() != null
                ? delegation.getOrganizationId() : delegation.getTeamId();

        return new NotificationDeliveryRequest(
                recipientUserId,
                "SCHEDULE_PROXY_DELEGATION",
                NotificationPriority.NORMAL,
                title, body,
                SOURCE_TYPE, delegation.getScheduleId(),
                scopeType, scopeId,
                "/schedules/" + delegation.getScheduleId(), null);
    }

    private String displayName(Long userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse("");
    }
}
