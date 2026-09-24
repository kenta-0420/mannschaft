package com.mannschaft.app.event.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.event.event.EventDelegationNotificationEvent;
import com.mannschaft.app.event.repository.EventDelegationRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * イベント代理出席の通知配送リスナー（F03.10 §8 / Issue #2990 L5 TX_NOTIFY_BARE 是正）。
 *
 * <h2>是正前の欠陥 — 何が巻き戻っていたか</h2>
 * <p>是正前の本クラスは素の {@code @Component} で、{@link EventDelegationService} の
 * {@code @Transactional} メソッドから<b>同期的に呼ばれていた</b>。内部の
 * {@code NotificationService#createNotification} は既定の {@code REQUIRED} 伝播で
 * 呼び出し元の業務トランザクションにそのまま参加するため、通知側の DB 例外は rollback-only を残し、
 * <b>代理の指定・承認・拒否・取消という業務処理ごと巻き戻っていた</b>。
 * schedule ドメインで L2（PR #3065）が是正した {@code ScheduleDelegationNotifier} と同一の欠陥である。</p>
 *
 * <p>巻き戻る業務処理は台帳の5件それぞれで次のとおり。</p>
 * <ul>
 *   <li>{@code createDelegation} — 委任行の作成と、RSVP モードでの委任者
 *       {@code NOT_ATTENDING} 反映（§5.4）。自動承認時は代理人の {@code ATTENDING} 反映と
 *       F08.3 連携イベント発火（§5.5）も同じトランザクションにある。</li>
 *   <li>{@code accept} — {@code ACCEPTED} への状態遷移、代理人 RSVP の {@code ATTENDING} 反映、
 *       F08.3 連携イベント発火。</li>
 *   <li>{@code reject} — {@code REJECTED} への状態遷移。</li>
 *   <li>{@code cancelInternal} — {@code CANCELLED} への状態遷移。委任者本人の取消
 *       （{@code withdraw}）とシステム都合の取消（{@code cancelBySystem}）の共通経路。</li>
 *   <li>{@code cancelOnMemberLeft} — 退会連動の {@code CANCELLED} 化。呼び出し元は
 *       {@code EventDelegationMembershipListener#handleMembershipEnded}（退会イベント連動）と
 *       {@code ProxyDelegationCleanupBatchService#cleanupEventDelegations}（日次バッチ）の2つで
 *       ある（grep 実測）。後者では 1 件の通知失敗でその委任の取消が巻き戻ったうえ、例外が
 *       ループの外まで伝播して<b>その回の残りの孤立委任が一切処理されなくなる</b>
 *       （取りこぼしは翌日の実行まで放置される）。
 *       <b>なお「その回の取消が全件巻き戻る」ことはない</b>: {@code ProxyDelegationCleanupBatchService}
 *       はクラスに {@code @Transactional} を持たず、{@code execute()} が
 *       {@code cleanupEventDelegations()} を<b>自己呼び出し</b>しているためメソッドの
 *       {@code @Transactional} が Spring プロキシを経由せず効いていない（本 PR とは無関係の既存の
 *       潜在バグであり、ここでは触れていない）。</li>
 * </ul>
 *
 * <h2>是正後</h2>
 * <p>{@link EventDelegationService} は {@link EventDelegationNotificationEvent} を
 * publish するだけに留め、本クラスが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で
 * 受け取って {@link NotificationDeliveryRunner#sendOne}（1 件 = 1 独立トランザクション）へ委譲する。
 * 通知は 1 通知 1 受信者であるため、受信者ごとの隔離はイベント 1 件ごとの隔離と一致する。</p>
 *
 * <h2>意図的な挙動変更: 3 種の通知に Push/WebSocket 配信が付く</h2>
 * <p>是正前は種別ごとに {@code dispatch} の有無を分けており、承認 / 取消 / 委任者退会の 3 種は
 * <b>IN_APP のみ</b>（Push・WebSocket なし）だった。{@link NotificationDeliveryRunner#sendOne} は
 * create + dispatch であるため、以後この 3 種も Push / WebSocket で配信される。
 * L2 の {@code ScheduleDelegationNotifier} と同じ判断であり、同じ F03.10 の通知が
 * schedule 側と event 側で非対称に配信されるほうが不自然である。</p>
 *
 * <h2>業務本文はイベントに載せず読み直す</h2>
 * <p>委任者名・代理人名・イベント ID・スコープはいずれも業務データであるため、
 * {@code delegationId} から読み直す。読み直しに失敗した場合は握りつぶさず ERROR ログを残して
 * 配送を中止する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventDelegationNotifier {

    private static final String SOURCE_TYPE = "EVENT";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final EventDelegationRepository delegationRepository;
    private final UserRepository userRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "代理出席は行事（CORE）の一部であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと代理人は自分に代理依頼が来たこと・委任者は代理が拒否されたことを知らぬまま"
                    + "行事当日を迎え、出欠の実態と記録が食い違う。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventDelegationNotification(EventDelegationNotificationEvent event) {
        if (event.delegationId() == null || event.kind() == null) {
            return;
        }

        // 業務本文（委任・イベント・スコープ）の読み直し。失敗は握りつぶさず配送を中止する。
        EventDelegationEntity delegation;
        try {
            delegation = delegationRepository.findById(event.delegationId()).orElse(null);
        } catch (Exception e) {
            log.error("イベント代理出席通知の委任読み直しに失敗しました（配送中止）: delegationId={}, kind={}",
                    event.delegationId(), event.kind(), e);
            return;
        }
        if (delegation == null) {
            log.error("イベント代理出席通知の委任が読み直し時点で存在しません（配送中止）: delegationId={}, kind={}",
                    event.delegationId(), event.kind());
            return;
        }

        try {
            NotificationDeliveryRequest request = buildRequest(delegation, event.kind());
            NotificationDeliveryResult result = notificationDeliveryRunner.sendOne(request);
            if (result == NotificationDeliveryResult.VISIBILITY_DENIED) {
                // visibility deny（例外ではない）。NotificationService 側で既に WARN 済み。
                log.warn("イベント代理出席通知が visibility deny によりスキップされました: "
                                + "recipientUserId={}, delegationId={}, kind={}",
                        request.recipientUserId(), event.delegationId(), event.kind());
            }
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TX外なので rollback で消えない。
            log.error("イベント代理出席通知の配送に失敗しました: delegationId={}, kind={}",
                    event.delegationId(), event.kind(), e);
        }
    }

    /** 通知配送要求を組み立てる（業務TX外・AFTER_COMMIT 後に実行される）。 */
    private NotificationDeliveryRequest buildRequest(
            EventDelegationEntity delegation, EventDelegationNotificationEvent.Kind kind) {
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
                "EVENT_PROXY_DELEGATION",
                NotificationPriority.NORMAL,
                title, body,
                SOURCE_TYPE, delegation.getEventId(),
                scopeType, scopeId,
                "/events/" + delegation.getEventId(), null);
    }

    private String displayName(Long userId) {
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse("");
    }
}
