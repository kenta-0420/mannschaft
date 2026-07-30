package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.ProxyVoteScopeType;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.dto.AttendanceResponse;
import com.mannschaft.app.proxyvote.dto.DelegateRequest;
import com.mannschaft.app.proxyvote.dto.DelegationResponse;
import com.mannschaft.app.proxyvote.dto.ReviewDelegationRequest;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 委任状サービス。委任状の提出・承認/却下・取り下げを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyDelegationService {

    private final ProxyVoteSessionService sessionService;
    private final ProxyDelegationRepository delegationRepository;
    private final ProxyVoteRepository voteRepository;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteMapper mapper;
    private final MembershipRepository membershipRepository;
    private final AccessControlService accessControlService;

    /**
     * 委任状を提出する。
     *
     * <p>認可根治戦役 Wave7: 兄弟の {@link ProxyVoteCastService#castVote} と同一の
     * {@link AccessControlService#checkMembership} を敷き、セッションスコープの会員であることを
     * 要求する（票の水増し防止）。</p>
     */
    @Transactional
    public DelegationResponse delegate(Long sessionId, DelegateRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);
        // 認可: 議決権 = セッションスコープの会員であること（票の水増し防止・castVoteと同一方式）
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());

        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }
        if (session.getIsAnonymous()) {
            throw new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_ALLOWED_ANONYMOUS);
        }
        if (voteRepository.existsBySessionIdAndUserId(sessionId, currentUserId)) {
            throw new BusinessException(ProxyVoteErrorCode.ALREADY_VOTED_CANNOT_DELEGATE);
        }
        if (delegationRepository.existsBySessionIdAndDelegatorId(sessionId, currentUserId)) {
            throw new BusinessException(ProxyVoteErrorCode.ALREADY_DELEGATED);
        }

        boolean isBlank = request.getIsBlank() != null && request.getIsBlank();
        if (!isBlank) {
            if (request.getDelegateId() == null) {
                throw new BusinessException(ProxyVoteErrorCode.DELEGATE_OUT_OF_SCOPE);
            }
            if (request.getDelegateId().equals(currentUserId)) {
                throw new BusinessException(ProxyVoteErrorCode.SELF_DELEGATION);
            }
            // 代理人のスコープ所属チェックは UserRoleRepository 経由で実装予定
        }

        DelegationStatus initialStatus = session.getIsAutoAcceptDelegation()
                ? DelegationStatus.ACCEPTED : DelegationStatus.SUBMITTED;

        ProxyDelegationEntity delegation = ProxyDelegationEntity.builder()
                .sessionId(sessionId)
                .delegatorId(currentUserId)
                .delegateId(isBlank ? null : request.getDelegateId())
                .isBlank(isBlank)
                .electronicSealId(request.getElectronicSealId())
                .reason(request.getReason())
                .status(initialStatus)
                .build();
        delegation = delegationRepository.save(delegation);

        log.info("委任状提出: delegationId={}, sessionId={}, delegatorId={}", delegation.getId(), sessionId, currentUserId);
        return mapper.toDelegationResponse(delegation);
    }

    /**
     * 委任状を取り下げる。
     *
     * <p>認可根治戦役 Wave7: {@link #delegate} と同一の理由で
     * {@link AccessControlService#checkMembership} を敷く。</p>
     */
    @Transactional
    public void cancelDelegation(Long sessionId, Long currentUserId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        ProxyDelegationEntity delegation = delegationRepository.findBySessionIdAndDelegatorId(sessionId, currentUserId)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_FOUND));

        if (delegation.getStatus() == DelegationStatus.REJECTED || delegation.getStatus() == DelegationStatus.CANCELLED) {
            throw new BusinessException(ProxyVoteErrorCode.DELEGATION_ALREADY_RESOLVED);
        }

        if (delegation.getStatus() == DelegationStatus.SUBMITTED) {
            delegationRepository.delete(delegation);
        } else if (delegation.getStatus() == DelegationStatus.ACCEPTED) {
            // 代理投票済みの場合はカウント補正
            List<ProxyVoteEntity> proxyVotes = voteRepository.findByDelegationId(delegation.getId());
            for (ProxyVoteEntity pv : proxyVotes) {
                ProxyVoteMotionEntity motion = motionRepository.findById(pv.getMotionId()).orElse(null);
                if (motion != null) {
                    motion.decrementVoteCount(pv.getVoteType());
                    motionRepository.save(motion);
                }
                voteRepository.delete(pv);
            }
            delegation.cancel();
            delegationRepository.save(delegation);
        }

        log.info("委任状取り下げ: sessionId={}, delegatorId={}", sessionId, currentUserId);
    }

    /**
     * 委任状を承認/却下する。
     */
    @Transactional
    public DelegationResponse reviewDelegation(Long delegationId, ReviewDelegationRequest request, Long reviewerId) {
        ProxyDelegationEntity delegation = delegationRepository.findById(delegationId)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_FOUND));

        // 認可: 当該セッションスコープの管理者のみ承認/却下可（委任 = 票の移転のため BOLA 厳禁）
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(delegation.getSessionId());
        accessControlService.checkAdminOrAbove(reviewerId, session.resolveScopeId(), session.scopeTypeName());

        if (delegation.getStatus() != DelegationStatus.SUBMITTED) {
            throw new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_SUBMITTED);
        }

        DelegationStatus newStatus = DelegationStatus.valueOf(request.getStatus());
        if (newStatus == DelegationStatus.ACCEPTED) {
            delegation.accept(reviewerId);
        } else if (newStatus == DelegationStatus.REJECTED) {
            delegation.reject(reviewerId);
        } else {
            throw new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_SUBMITTED);
        }

        delegation = delegationRepository.save(delegation);
        log.info("委任状レビュー: delegationId={}, status={}", delegationId, newStatus);
        return mapper.toDelegationResponse(delegation);
    }

    /**
     * 出席・委任状況一覧を取得する。
     *
     * <p>認可根治戦役 Wave7: {@link ProxyVoteCastService#castVote} と同一の
     * {@link AccessControlService#checkMembership} を敷き、セッションスコープの会員に
     * 限定する。</p>
     */
    public AttendanceResponse getAttendance(Long sessionId, Long currentUserId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());

        long votedCount = voteRepository.countDistinctVotersBySessionId(sessionId);
        long delegatedCount = delegationRepository.countBySessionIdAndStatus(sessionId, DelegationStatus.ACCEPTED);
        long notResponded = session.getEligibleCount() - votedCount - delegatedCount;
        if (notResponded < 0) notResponded = 0;

        // メンバー詳細一覧は UserRepository 結合で構築予定

        return AttendanceResponse.builder()
                .sessionId(sessionId)
                .eligibleCount(session.getEligibleCount())
                .summary(AttendanceResponse.SummaryResponse.builder()
                        .votedCount(votedCount)
                        .delegatedCount(delegatedCount)
                        .notRespondedCount(notResponded)
                        .build())
                .members(List.of()) // メンバー一覧は上記構築完了後に設定
                .build();
    }

    // =========================================================
    // F03.10 代理出席連携（§5.5 / §5.6 #9〜#12）
    // =========================================================

    /**
     * F03.10 イベント代理指定 POST 時の投票セッション事前検証（§5.6 #9〜#11）。
     *
     * <p>event ドメインから読み取り専用で呼び出す（クロスドメインだが書き込みは伴わない）。
     * 違反時は呼び出し元（event ドメイン）が 422 を返せるよう {@code true/false} を返す。
     * #12（委任者の既存 proxy_delegation）はエラーにせず連携のみスキップする warning 扱いのため、
     * 本メソッドの判定には含めない。</p>
     *
     * @param sessionId      投票セッション ID
     * @param eventScopeType イベントスコープ種別（"TEAM"/"ORGANIZATION"）
     * @param eventScopeId   イベントスコープ ID
     * @param delegateId     代理人 user_id
     * @return #9〜#11 をすべて満たす場合 true
     */
    public boolean isSessionEligibleForEventDelegation(Long sessionId, String eventScopeType,
                                                       Long eventScopeId, Long delegateId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOptional(sessionId).orElse(null);
        if (session == null) {
            return false; // #9: 存在しない
        }
        // #9: 同スコープか（scope_type / scope_id 一致）
        if (!isSameScope(session, eventScopeType, eventScopeId)) {
            return false;
        }
        // #9: 無記名でない
        if (Boolean.TRUE.equals(session.getIsAnonymous())) {
            return false;
        }
        // #10: OPEN である
        if (session.getStatus() != SessionStatus.OPEN) {
            return false;
        }
        // #11: 代理人がそのセッションの投票資格（MEMBER+）を満たす
        return hasVotingRight(session, delegateId);
    }

    /**
     * F03.10 イベント代理出席 ACCEPTED 確定後の proxy_delegations 連携作成（§5.5）。
     *
     * <p>proxyvote ドメインの {@code @TransactionalEventListener(AFTER_COMMIT)} から別トランザクションで呼ぶ。
     * §5.5 の検証（同スコープ / OPEN / 無記名でない / 投票資格 / 既存委任なし）をすべて満たす場合のみ作成し、
     * 作成した proxy_delegations.id を返す。いずれかを満たさない場合は warning ログを残してスキップし
     * {@code null} を返す（代理出席自体は成立済みのためエラーにしない・§5.5 / §5.6 #12）。</p>
     *
     * @param sessionId      投票セッション ID
     * @param delegatorId    委任者 user_id
     * @param delegateId     代理人 user_id
     * @param eventScopeType イベントスコープ種別（"TEAM"/"ORGANIZATION"）
     * @param eventScopeId   イベントスコープ ID
     * @return 作成した proxy_delegations.id。スキップ時は null。
     */
    @Transactional
    public Long createFromEventDelegation(Long sessionId, Long delegatorId, Long delegateId,
                                          String eventScopeType, Long eventScopeId) {
        Optional<ProxyVoteSessionEntity> opt = sessionService.findSessionOptional(sessionId);
        if (opt.isEmpty()) {
            log.warn("代理出席→投票委任 連携スキップ: セッション不在 sessionId={}", sessionId);
            return null;
        }
        ProxyVoteSessionEntity session = opt.get();

        if (!isSameScope(session, eventScopeType, eventScopeId)) {
            log.warn("代理出席→投票委任 連携スキップ: スコープ不一致 sessionId={}", sessionId);
            return null;
        }
        if (session.getStatus() != SessionStatus.OPEN) {
            log.warn("代理出席→投票委任 連携スキップ: OPEN以外 sessionId={}, status={}", sessionId, session.getStatus());
            return null;
        }
        if (Boolean.TRUE.equals(session.getIsAnonymous())) {
            log.warn("代理出席→投票委任 連携スキップ: 無記名 sessionId={}", sessionId);
            return null;
        }
        if (!hasVotingRight(session, delegateId)) {
            log.warn("代理出席→投票委任 連携スキップ: 代理人に投票資格なし sessionId={}, delegateId={}", sessionId, delegateId);
            return null;
        }
        // 委任者が既存の proxy_delegation を持つ場合はスキップ（既存の投票委任を破壊しない・§5.5 / §5.6 #12）
        if (delegationRepository.existsBySessionIdAndDelegatorId(sessionId, delegatorId)) {
            log.warn("代理出席→投票委任 連携スキップ: 委任者の既存委任あり sessionId={}, delegatorId={}", sessionId, delegatorId);
            return null;
        }

        DelegationStatus initialStatus = Boolean.TRUE.equals(session.getIsAutoAcceptDelegation())
                ? DelegationStatus.ACCEPTED : DelegationStatus.SUBMITTED;

        ProxyDelegationEntity delegation = ProxyDelegationEntity.builder()
                .sessionId(sessionId)
                .delegatorId(delegatorId)
                .delegateId(delegateId)
                .isBlank(false)
                .reason("代理出席連携")
                .status(initialStatus)
                .build();
        delegation = delegationRepository.save(delegation);

        log.info("代理出席→投票委任 連携作成: proxyDelegationId={}, sessionId={}, delegatorId={}, status={}",
                delegation.getId(), sessionId, delegatorId, initialStatus);
        return delegation.getId();
    }

    private boolean isSameScope(ProxyVoteSessionEntity session, String eventScopeType, Long eventScopeId) {
        if (eventScopeType == null || eventScopeId == null) {
            return false;
        }
        ProxyVoteScopeType sessionScope = session.getScopeType();
        if (sessionScope == ProxyVoteScopeType.TEAM) {
            return "TEAM".equals(eventScopeType) && eventScopeId.equals(session.getTeamId());
        }
        if (sessionScope == ProxyVoteScopeType.ORGANIZATION) {
            return "ORGANIZATION".equals(eventScopeType) && eventScopeId.equals(session.getOrganizationId());
        }
        return false;
    }

    private boolean hasVotingRight(ProxyVoteSessionEntity session, Long userId) {
        if (session.getScopeType() == ProxyVoteScopeType.TEAM) {
            return membershipRepository.existsActiveByUserAndScope(userId, ScopeType.TEAM, session.getTeamId());
        }
        return membershipRepository.existsActiveByUserAndScope(userId, ScopeType.ORGANIZATION, session.getOrganizationId());
    }
}
