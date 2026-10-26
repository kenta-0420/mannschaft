package com.mannschaft.app.proxyvote.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.event.event.EventDelegationAcceptedEvent;
import com.mannschaft.app.event.service.EventDelegationService;
import com.mannschaft.app.proxyvote.service.ProxyDelegationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F03.10 §5.5: イベント代理出席 ACCEPTED 確定を受けて投票代理（proxy_delegations）を自動作成するリスナー。
 *
 * <p>event ドメインの ACCEPTED 確定トランザクションのコミット後（{@code AFTER_COMMIT}）に発火し、
 * proxyvote ドメインの別トランザクションで連携処理を行う（CLAUDE.md 原則5: クロスドメインはイベント駆動）。
 * 手本: {@code com.mannschaft.app.scopefolder.listener.MembershipEventListener}。</p>
 *
 * <p>処理:</p>
 * <ol>
 *   <li>{@code proxyVoteSessionId} が null なら何もしない（連携対象外）</li>
 *   <li>{@link ProxyDelegationService#createFromEventDelegation} で §5.5 検証 + proxy_delegations 作成
 *       （OPEN/無記名/同スコープ/投票資格/既存委任なし を満たさなければ warning スキップ → null 返却）</li>
 *   <li>作成された proxy_delegations.id を {@link EventDelegationService#linkProxyDelegation} で
 *       event_delegations.proxy_delegation_id に別トランザクションで逆設定</li>
 * </ol>
 *
 * <p>連携処理の失敗が代理出席本体（既にコミット済み）に影響しないよう、例外は握り潰さず
 * ログに残すが、AFTER_COMMIT のため呼び出し元トランザクションは巻き戻らない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventDelegationAcceptedListener {

    private final ProxyDelegationService proxyDelegationService;
    private final EventDelegationService eventDelegationService;

    /**
     * イベント代理出席 ACCEPTED 確定イベントを受信して投票代理を連携作成する。
     *
     * @param event イベント代理出席 ACCEPTED イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "殿の裁定: 上流のイベント代理出席は非ゲートのため閉栓中も ACCEPTED が確定しうる。落とすと代理出席は成立しているのに投票代理が存在しない乖離が残り、イベントは再生されない")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleEventDelegationAccepted(EventDelegationAcceptedEvent event) {
        if (event.proxyVoteSessionId() == null) {
            return; // 投票代理連携の指定なし
        }
        try {
            Long proxyDelegationId = proxyDelegationService.createFromEventDelegation(
                    event.proxyVoteSessionId(),
                    event.delegatorId(),
                    event.delegateId(),
                    event.scopeType() != null ? event.scopeType().name() : null,
                    event.scopeId());
            // 別トランザクションで逆設定（スキップ時は proxyDelegationId == null で no-op）
            eventDelegationService.linkProxyDelegation(event.delegationId(), proxyDelegationId);
        } catch (Exception ex) {
            log.warn("EventDelegationAcceptedEvent 連携失敗: delegationId={}, sessionId={}, error={}",
                    event.delegationId(), event.proxyVoteSessionId(), ex.getMessage(), ex);
        }
    }
}
