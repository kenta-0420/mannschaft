package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventDelegationStatus;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventDelegationRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.proxyvote.service.ProxyDelegationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * イベント代理出席のバリデーション（F03.10 §5.6）。
 *
 * <p>{@link com.mannschaft.app.schedule.service.ScheduleDelegationValidator} と同型 + イベント固有の
 * 投票セッション事前検証（§5.6 #9〜#11）を行う。投票セッション検証は proxyvote ドメインの
 * {@link ProxyDelegationService#isSessionEligibleForEventDelegation} に委譲する（読み取りのみのクロスドメイン参照）。</p>
 */
@Component
@RequiredArgsConstructor
public class EventDelegationValidator {

    /** アクティブ（重複・連鎖判定対象）とみなすステータス群。 */
    private static final List<EventDelegationStatus> ACTIVE_STATUSES =
            List.of(EventDelegationStatus.PENDING, EventDelegationStatus.ACCEPTED);

    private final MembershipRepository membershipRepository;
    private final EventDelegationRepository delegationRepository;
    private final ProxyDelegationService proxyDelegationService;

    /**
     * 代理指定時のバリデーションを実行する（§5.6 #1〜#11）。
     *
     * @param event              対象イベント
     * @param delegatorId        委任者 user_id
     * @param delegateId         代理人 user_id
     * @param proxyVoteSessionId 連携する投票セッション ID（任意・null 可）
     */
    public void validateForCreate(EventEntity event, Long delegatorId, Long delegateId,
                                  Long proxyVoteSessionId) {
        // #1: allow_proxy_attendance = TRUE
        if (!Boolean.TRUE.equals(event.getAllowProxyAttendance())) {
            throw new BusinessException(EventErrorCode.DELEGATION_NOT_ALLOWED);
        }
        // #7: イベントが CANCELLED/COMPLETED でない
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.COMPLETED) {
            throw new BusinessException(EventErrorCode.DELEGATION_INVALID_EVENT_STATUS);
        }
        // #4: 自己代理不可
        if (delegatorId.equals(delegateId)) {
            throw new BusinessException(EventErrorCode.DELEGATION_SELF_DELEGATION);
        }

        // スコープ解決
        ScopeType scopeType = event.getScopeType() == EventScopeType.ORGANIZATION
                ? ScopeType.ORGANIZATION : ScopeType.TEAM;
        Long scopeId = event.getScopeId();

        // #2: 委任者はスコープのメンバー（403）
        if (!membershipRepository.existsActiveByUserAndScope(delegatorId, scopeType, scopeId)) {
            throw new BusinessException(EventErrorCode.DELEGATION_DELEGATOR_NOT_MEMBER);
        }
        // #3: 代理人はスコープのメンバー（422）
        if (!membershipRepository.existsActiveByUserAndScope(delegateId, scopeType, scopeId)) {
            throw new BusinessException(EventErrorCode.DELEGATION_DELEGATE_NOT_MEMBER);
        }

        // #5: 委任者のアクティブ代理が既に存在しない（409）
        delegationRepository
                .findFirstByEventIdAndDelegatorIdAndStatusIn(event.getId(), delegatorId, ACTIVE_STATUSES)
                .ifPresent(existing -> {
                    throw new BusinessException(EventErrorCode.DELEGATION_ALREADY_EXISTS);
                });

        // #6: 連鎖代理禁止（422）
        if (delegationRepository.existsByDelegateIdAndStatusIn(delegateId, ACTIVE_STATUSES)) {
            throw new BusinessException(EventErrorCode.DELEGATION_CHAINED);
        }

        // #9〜#11: proxyVoteSessionId 指定時の事前検証（422）
        if (proxyVoteSessionId != null) {
            boolean eligible = proxyDelegationService.isSessionEligibleForEventDelegation(
                    proxyVoteSessionId, event.getScopeType().name(), scopeId, delegateId);
            if (!eligible) {
                throw new BusinessException(EventErrorCode.DELEGATION_PROXY_VOTE_INVALID);
            }
        }
    }
}
