package com.mannschaft.app.event.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.event.service.EventDelegationService;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.event.MembershipEndedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * F03.10 §5.8: メンバー退会時にイベント代理出席を自動取消するリスナー。
 *
 * <p>{@link com.mannschaft.app.schedule.listener.ScheduleDelegationMembershipListener} のイベント版。
 * {@link MembershipEndedEvent} を受信し、退会したユーザーが関与する PENDING/ACCEPTED 代理を
 * CANCELLED にして相手方に通知する（AFTER_COMMIT + REQUIRES_NEW・CLAUDE.md 原則5）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventDelegationMembershipListener {

    private final EventDelegationService delegationService;

    /**
     * メンバー退会時に該当ユーザーが関与するイベント代理を取り消す。
     *
     * @param event メンバーシップ終了イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。所属終了に伴うイベント代理権限の失効。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMembershipEnded(MembershipEndedEvent event) {
        try {
            Long organizationId = event.scopeType() == ScopeType.ORGANIZATION ? event.scopeId() : null;
            Long teamId = event.scopeType() == ScopeType.TEAM ? event.scopeId() : null;

            List<EventDelegationEntity> delegations =
                    delegationService.findActiveByScopeAndInvolvedUser(organizationId, teamId, event.userId());
            for (EventDelegationEntity delegation : delegations) {
                delegationService.cancelOnMemberLeft(delegation, event.userId());
            }
            if (!delegations.isEmpty()) {
                log.info("退会連動 イベント代理取消: userId={}, scopeType={}, scopeId={}, 件数={}",
                        event.userId(), event.scopeType(), event.scopeId(), delegations.size());
            }
        } catch (Exception ex) {
            log.warn("MembershipEndedEvent イベント代理取消失敗: userId={}, scopeType={}, scopeId={}, error={}",
                    event.userId(), event.scopeType(), event.scopeId(), ex.getMessage(), ex);
        }
    }
}
