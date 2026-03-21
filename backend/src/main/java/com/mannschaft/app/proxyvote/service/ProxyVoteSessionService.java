package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.AttachmentTargetType;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.MotionResult;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.ProxyVoteScopeType;
import com.mannschaft.app.proxyvote.QuorumType;
import com.mannschaft.app.proxyvote.RequiredApproval;
import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.VoteType;
import com.mannschaft.app.proxyvote.VotingStatus;
import com.mannschaft.app.proxyvote.dto.AttachmentResponse;
import com.mannschaft.app.proxyvote.dto.CastVoteRequest;
import com.mannschaft.app.proxyvote.dto.CastVoteResponse;
import com.mannschaft.app.proxyvote.dto.CloneSessionRequest;
import com.mannschaft.app.proxyvote.dto.CreateSessionRequest;
import com.mannschaft.app.proxyvote.dto.FinalizeRequest;
import com.mannschaft.app.proxyvote.dto.FinalizeResponse;
import com.mannschaft.app.proxyvote.dto.MotionRequest;
import com.mannschaft.app.proxyvote.dto.MotionResponse;
import com.mannschaft.app.proxyvote.dto.MyStatusResponse;
import com.mannschaft.app.proxyvote.dto.QuorumStatusResponse;
import com.mannschaft.app.proxyvote.dto.RemindResponse;
import com.mannschaft.app.proxyvote.dto.SessionListResponse;
import com.mannschaft.app.proxyvote.dto.SessionResponse;
import com.mannschaft.app.proxyvote.dto.UpdateSessionRequest;
import com.mannschaft.app.proxyvote.dto.VoteResultsResponse;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteAttachmentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteAttachmentRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 投票セッションサービス。セッション・議案のCRUDと投票ライフサイクルを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyVoteSessionService {

    private final ProxyVoteSessionRepository sessionRepository;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteAttachmentRepository attachmentRepository;
    private final ProxyVoteRepository voteRepository;
    private final ProxyDelegationRepository delegationRepository;
    private final ProxyVoteMapper mapper;

    /**
     * 投票セッション一覧を取得する。
     */
    public Page<SessionListResponse> listSessions(ProxyVoteScopeType scopeType, Long teamId,
                                                   Long organizationId, SessionStatus status,
                                                   Long currentUserId, Pageable pageable) {
        Page<ProxyVoteSessionEntity> sessions;
        if (scopeType == ProxyVoteScopeType.TEAM) {
            sessions = status != null
                    ? sessionRepository.findByScopeTypeAndTeamIdAndStatusOrderByCreatedAtDesc(scopeType, teamId, status, pageable)
                    : sessionRepository.findByScopeTypeAndTeamIdOrderByCreatedAtDesc(scopeType, teamId, pageable);
        } else {
            sessions = status != null
                    ? sessionRepository.findByScopeTypeAndOrganizationIdAndStatusOrderByCreatedAtDesc(scopeType, organizationId, status, pageable)
                    : sessionRepository.findByScopeTypeAndOrganizationIdOrderByCreatedAtDesc(scopeType, organizationId, pageable);
        }
        return sessions.map(s -> toSessionListResponse(s, currentUserId));
    }

    /**
     * 投票セッション詳細を取得する。
     */
    public SessionResponse getSession(Long id, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        return toSessionResponse(session, currentUserId);
    }

    /**
     * 投票セッションを作成する。
     */
    @Transactional
    public SessionResponse createSession(CreateSessionRequest request, Long currentUserId) {
        ProxyVoteScopeType scopeType = ProxyVoteScopeType.valueOf(request.getScopeType());
        ResolutionMode resolutionMode = ResolutionMode.valueOf(request.getResolutionMode());

        validateScopeIds(scopeType, request.getTeamId(), request.getOrganizationId());
        if (resolutionMode == ResolutionMode.MEETING && request.getMeetingDate() == null) {
            throw new BusinessException(ProxyVoteErrorCode.MEETING_DATE_REQUIRED);
        }

        QuorumType quorumType = request.getQuorumType() != null
                ? QuorumType.valueOf(request.getQuorumType()) : QuorumType.MAJORITY;
        validateQuorumThreshold(quorumType, request.getQuorumThreshold());

        // eligible_count はスコープ内メンバー数のスナップショット（TODO: 実際のメンバー数取得）
        int eligibleCount = 0;

        ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                .scopeType(scopeType)
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .title(request.getTitle())
                .description(request.getDescription())
                .resolutionMode(resolutionMode)
                .meetingDate(request.getMeetingDate())
                .votingStartAt(request.getVotingStartAt())
                .votingEndAt(request.getVotingEndAt())
                .isAnonymous(request.getIsAnonymous() != null ? request.getIsAnonymous() : false)
                .quorumType(quorumType)
                .quorumThreshold(request.getQuorumThreshold())
                .eligibleCount(eligibleCount)
                .isAutoAcceptDelegation(request.getIsAutoAcceptDelegation() != null ? request.getIsAutoAcceptDelegation() : false)
                .remindBeforeHours(request.getRemindBeforeHours())
                .createdBy(currentUserId)
                .build();
        session = sessionRepository.save(session);

        if (request.getMotions() != null && !request.getMotions().isEmpty()) {
            createMotions(session.getId(), request.getMotions());
        }

        return toSessionResponse(session, currentUserId);
    }

    /**
     * 投票セッションを更新する。
     */
    @Transactional
    public SessionResponse updateSession(Long id, UpdateSessionRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);

        if (session.getStatus() == SessionStatus.CLOSED || session.getStatus() == SessionStatus.FINALIZED) {
            throw new BusinessException(ProxyVoteErrorCode.SESSION_NOT_UPDATABLE);
        }

        if (session.getStatus() == SessionStatus.DRAFT) {
            ResolutionMode resolutionMode = request.getResolutionMode() != null
                    ? ResolutionMode.valueOf(request.getResolutionMode()) : session.getResolutionMode();
            QuorumType quorumType = request.getQuorumType() != null
                    ? QuorumType.valueOf(request.getQuorumType()) : session.getQuorumType();
            validateQuorumThreshold(quorumType, request.getQuorumThreshold());

            if (resolutionMode == ResolutionMode.MEETING && request.getMeetingDate() == null && session.getMeetingDate() == null) {
                throw new BusinessException(ProxyVoteErrorCode.MEETING_DATE_REQUIRED);
            }

            session.update(request.getTitle(), request.getDescription(),
                    request.getVotingStartAt(), request.getVotingEndAt(),
                    request.getIsAnonymous() != null ? request.getIsAnonymous() : session.getIsAnonymous(),
                    quorumType, request.getQuorumThreshold(),
                    request.getIsAutoAcceptDelegation() != null ? request.getIsAutoAcceptDelegation() : session.getIsAutoAcceptDelegation(),
                    resolutionMode, request.getMeetingDate() != null ? request.getMeetingDate() : session.getMeetingDate(),
                    request.getRemindBeforeHours());
        } else {
            // OPEN: 限定フィールドのみ
            session.updateWhenOpen(request.getTitle(), request.getDescription(),
                    request.getVotingEndAt(),
                    request.getIsAutoAcceptDelegation() != null ? request.getIsAutoAcceptDelegation() : session.getIsAutoAcceptDelegation());
        }

        session = sessionRepository.save(session);
        return toSessionResponse(session, currentUserId);
    }

    /**
     * 投票セッションを論理削除する（DRAFT のみ）。
     */
    @Transactional
    public void deleteSession(Long id) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        if (session.getStatus() != SessionStatus.DRAFT) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_DRAFT);
        }
        session.softDelete();
        sessionRepository.save(session);
    }

    /**
     * 投票受付を開始する（DRAFT → OPEN）。
     */
    @Transactional
    public SessionResponse openSession(Long id, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        if (session.getStatus() != SessionStatus.DRAFT) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_DRAFT);
        }

        long motionCount = motionRepository.countBySessionId(id);
        if (motionCount == 0) {
            throw new BusinessException(ProxyVoteErrorCode.NO_MOTIONS);
        }

        // eligible_count を再スナップショット（TODO: 実際のメンバー数取得）
        session.updateEligibleCount(session.getEligibleCount());
        session.changeStatus(SessionStatus.OPEN);

        // WRITTEN モードの場合、全議案を VOTING に
        if (session.getResolutionMode() == ResolutionMode.WRITTEN) {
            List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(id);
            motions.forEach(m -> m.changeVotingStatus(VotingStatus.VOTING));
            motionRepository.saveAll(motions);
        }

        session = sessionRepository.save(session);
        log.info("投票セッション OPEN: sessionId={}", id);
        return toSessionResponse(session, currentUserId);
    }

    /**
     * 投票を締め切る（OPEN → CLOSED）。
     */
    @Transactional
    public SessionResponse closeSession(Long id, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        if (session.getResolutionMode() == ResolutionMode.MEETING) {
            long notVotedCount = motionRepository.countBySessionIdAndVotingStatusNot(id, VotingStatus.VOTED);
            if (notVotedCount > 0) {
                throw new BusinessException(ProxyVoteErrorCode.NOT_ALL_MOTIONS_VOTED);
            }
        } else {
            // WRITTEN: 全議案を VOTED に
            List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(id);
            motions.forEach(m -> m.changeVotingStatus(VotingStatus.VOTED));
            motionRepository.saveAll(motions);
        }

        session.changeStatus(SessionStatus.CLOSED);
        session = sessionRepository.save(session);
        log.info("投票セッション CLOSED: sessionId={}", id);
        return toSessionResponse(session, currentUserId);
    }

    /**
     * 結果を確定する（CLOSED → FINALIZED）。
     */
    @Transactional
    public FinalizeResponse finalizeSession(Long id, FinalizeRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        if (session.getStatus() != SessionStatus.CLOSED) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_CLOSED);
        }

        QuorumStatusResponse quorumStatus = buildQuorumStatus(session);
        boolean quorumMet = quorumStatus.getIsMet();
        boolean force = request.getForce() != null && request.getForce();

        if (!quorumMet && !force) {
            return FinalizeResponse.builder()
                    .sessionId(id)
                    .status(SessionStatus.CLOSED.name())
                    .quorumMet(false)
                    .quorumStatus(quorumStatus)
                    .message("定足数に達していません。force=true で強制確定できますが、結果は参考決議となります。")
                    .build();
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(id);
        List<FinalizeResponse.MotionFinalizeResponse> motionResults = new ArrayList<>();

        // WRITTEN モード: 各議案の result を判定
        if (session.getResolutionMode() == ResolutionMode.WRITTEN) {
            for (ProxyVoteMotionEntity motion : motions) {
                MotionResult result = judgeMotionResult(motion);
                motion.setResult(result);
                motionResults.add(FinalizeResponse.MotionFinalizeResponse.builder()
                        .id(motion.getId())
                        .result(result.name())
                        .isAdvisory(!quorumMet)
                        .build());
            }
            motionRepository.saveAll(motions);
        } else {
            // MEETING: result は end-vote 時に確定済み
            for (ProxyVoteMotionEntity motion : motions) {
                motionResults.add(FinalizeResponse.MotionFinalizeResponse.builder()
                        .id(motion.getId())
                        .result(motion.getResult() != null ? motion.getResult().name() : null)
                        .isAdvisory(!quorumMet)
                        .build());
            }
        }

        session.changeStatus(SessionStatus.FINALIZED);
        sessionRepository.save(session);
        log.info("投票セッション FINALIZED: sessionId={}", id);

        return FinalizeResponse.builder()
                .sessionId(id)
                .status(SessionStatus.FINALIZED.name())
                .quorumMet(quorumMet)
                .motions(motionResults)
                .build();
    }

    /**
     * 投票する。
     */
    @Transactional
    public CastVoteResponse castVote(Long sessionId, CastVoteRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(sessionId);
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(sessionId);
        validateCastVoteRequest(session, motions, request);

        // 既に投票済みかチェック
        boolean alreadyVoted = voteRepository.existsBySessionIdAndUserId(sessionId, currentUserId);
        if (alreadyVoted) {
            throw new BusinessException(ProxyVoteErrorCode.ALREADY_VOTED);
        }

        // 委任状がある場合は自動キャンセル
        delegationRepository.findBySessionIdAndDelegatorId(sessionId, currentUserId)
                .ifPresent(delegation -> {
                    delegation.cancel();
                    delegationRepository.save(delegation);
                });

        // 本人の投票を記録
        LocalDateTime votedAt = LocalDateTime.now();
        int votedCount = 0;
        for (CastVoteRequest.VoteItem item : request.getVotes()) {
            VoteType voteType = VoteType.valueOf(item.getVoteType());
            ProxyVoteEntity vote = ProxyVoteEntity.builder()
                    .motionId(item.getMotionId())
                    .userId(currentUserId)
                    .voteType(voteType)
                    .isProxyVote(false)
                    .votedAt(votedAt)
                    .build();
            voteRepository.save(vote);

            // カウント更新
            ProxyVoteMotionEntity motion = motions.stream()
                    .filter(m -> m.getId().equals(item.getMotionId()))
                    .findFirst().orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
            motion.incrementVoteCount(voteType);
            motionRepository.save(motion);
            votedCount++;
        }

        // 委任を受けている場合: 代理投票を一括生成
        List<ProxyDelegationEntity> acceptedDelegations =
                delegationRepository.findBySessionIdAndDelegateIdAndStatus(sessionId, currentUserId, DelegationStatus.ACCEPTED);
        for (ProxyDelegationEntity delegation : acceptedDelegations) {
            for (CastVoteRequest.VoteItem item : request.getVotes()) {
                VoteType voteType = VoteType.valueOf(item.getVoteType());
                if (!voteRepository.existsByMotionIdAndUserId(item.getMotionId(), delegation.getDelegatorId())) {
                    ProxyVoteEntity proxyVote = ProxyVoteEntity.builder()
                            .motionId(item.getMotionId())
                            .userId(delegation.getDelegatorId())
                            .voteType(voteType)
                            .isProxyVote(true)
                            .delegationId(delegation.getId())
                            .votedAt(votedAt)
                            .build();
                    voteRepository.save(proxyVote);

                    ProxyVoteMotionEntity motion = motions.stream()
                            .filter(m -> m.getId().equals(item.getMotionId()))
                            .findFirst().orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
                    motion.incrementVoteCount(voteType);
                    motionRepository.save(motion);
                }
            }
        }

        return CastVoteResponse.builder()
                .sessionId(sessionId)
                .votedMotions(votedCount)
                .votedAt(votedAt)
                .build();
    }

    /**
     * 投票を変更する。
     */
    @Transactional
    public CastVoteResponse updateVote(Long sessionId, CastVoteRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(sessionId);
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        boolean alreadyVoted = voteRepository.existsBySessionIdAndUserId(sessionId, currentUserId);
        if (!alreadyVoted) {
            throw new BusinessException(ProxyVoteErrorCode.VOTE_NOT_FOUND);
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(sessionId);
        validateCastVoteRequest(session, motions, request);

        // 既存の投票を削除してカウント補正
        List<ProxyVoteEntity> existingVotes = voteRepository.findBySessionIdAndUserId(sessionId, currentUserId);
        for (ProxyVoteEntity existing : existingVotes) {
            ProxyVoteMotionEntity motion = motions.stream()
                    .filter(m -> m.getId().equals(existing.getMotionId()))
                    .findFirst().orElse(null);
            if (motion != null) {
                motion.decrementVoteCount(existing.getVoteType());
            }
            voteRepository.delete(existing);
        }

        // 代理投票も削除
        List<ProxyDelegationEntity> acceptedDelegations =
                delegationRepository.findBySessionIdAndDelegateIdAndStatus(sessionId, currentUserId, DelegationStatus.ACCEPTED);
        for (ProxyDelegationEntity delegation : acceptedDelegations) {
            List<ProxyVoteEntity> proxyVotes = voteRepository.findByDelegationId(delegation.getId());
            for (ProxyVoteEntity pv : proxyVotes) {
                ProxyVoteMotionEntity motion = motions.stream()
                        .filter(m -> m.getId().equals(pv.getMotionId()))
                        .findFirst().orElse(null);
                if (motion != null) {
                    motion.decrementVoteCount(pv.getVoteType());
                }
                voteRepository.delete(pv);
            }
        }
        motionRepository.saveAll(motions);

        // 新しい投票を記録（castVote と同じロジック）
        LocalDateTime votedAt = LocalDateTime.now();
        int votedCount = 0;
        for (CastVoteRequest.VoteItem item : request.getVotes()) {
            VoteType voteType = VoteType.valueOf(item.getVoteType());
            ProxyVoteEntity vote = ProxyVoteEntity.builder()
                    .motionId(item.getMotionId())
                    .userId(currentUserId)
                    .voteType(voteType)
                    .isProxyVote(false)
                    .votedAt(votedAt)
                    .build();
            voteRepository.save(vote);

            ProxyVoteMotionEntity motion = motions.stream()
                    .filter(m -> m.getId().equals(item.getMotionId()))
                    .findFirst().orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
            motion.incrementVoteCount(voteType);
            motionRepository.save(motion);
            votedCount++;
        }

        // 代理投票を再生成
        for (ProxyDelegationEntity delegation : acceptedDelegations) {
            for (CastVoteRequest.VoteItem item : request.getVotes()) {
                VoteType voteType = VoteType.valueOf(item.getVoteType());
                ProxyVoteEntity proxyVote = ProxyVoteEntity.builder()
                        .motionId(item.getMotionId())
                        .userId(delegation.getDelegatorId())
                        .voteType(voteType)
                        .isProxyVote(true)
                        .delegationId(delegation.getId())
                        .votedAt(votedAt)
                        .build();
                voteRepository.save(proxyVote);

                ProxyVoteMotionEntity motion = motions.stream()
                        .filter(m -> m.getId().equals(item.getMotionId()))
                        .findFirst().orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
                motion.incrementVoteCount(voteType);
                motionRepository.save(motion);
            }
        }

        return CastVoteResponse.builder()
                .sessionId(sessionId)
                .votedMotions(votedCount)
                .votedAt(votedAt)
                .build();
    }

    /**
     * セッションを複製する。
     */
    @Transactional
    public SessionResponse cloneSession(Long id, CloneSessionRequest request, Long currentUserId) {
        ProxyVoteSessionEntity source = findSessionOrThrow(id);

        String title = request.getTitle() != null ? request.getTitle() : source.getTitle() + "（コピー）";

        ProxyVoteSessionEntity clone = ProxyVoteSessionEntity.builder()
                .scopeType(source.getScopeType())
                .teamId(source.getTeamId())
                .organizationId(source.getOrganizationId())
                .title(title)
                .description(source.getDescription())
                .resolutionMode(source.getResolutionMode())
                .meetingDate(request.getMeetingDate())
                .isAnonymous(source.getIsAnonymous())
                .quorumType(source.getQuorumType())
                .quorumThreshold(source.getQuorumThreshold())
                .isAutoAcceptDelegation(source.getIsAutoAcceptDelegation())
                .remindBeforeHours(source.getRemindBeforeHours())
                .eligibleCount(0)
                .createdBy(currentUserId)
                .build();
        clone = sessionRepository.save(clone);

        // 議案をコピー
        List<ProxyVoteMotionEntity> sourceMotions = motionRepository.findBySessionIdOrderByMotionNumberAsc(id);
        for (ProxyVoteMotionEntity sm : sourceMotions) {
            ProxyVoteMotionEntity cm = ProxyVoteMotionEntity.builder()
                    .sessionId(clone.getId())
                    .motionNumber(sm.getMotionNumber())
                    .title(sm.getTitle())
                    .description(sm.getDescription())
                    .requiredApproval(sm.getRequiredApproval())
                    .build();
            motionRepository.save(cm);
        }

        return toSessionResponse(clone, currentUserId);
    }

    /**
     * 投票結果を取得する。
     */
    public VoteResultsResponse getResults(Long id) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(id);

        long votedCount = voteRepository.countDistinctVotersBySessionId(id);
        long delegatedCount = delegationRepository.countBySessionIdAndStatus(id, DelegationStatus.ACCEPTED);
        long notResponded = session.getEligibleCount() - votedCount - delegatedCount;
        if (notResponded < 0) notResponded = 0;

        QuorumStatusResponse quorumStatus = buildQuorumStatus(session);

        List<VoteResultsResponse.MotionResultResponse> motionResults = motions.stream()
                .map(m -> {
                    int total = m.getApproveCount() + m.getRejectCount() + m.getAbstainCount();
                    BigDecimal approveRate = total > 0
                            ? BigDecimal.valueOf(m.getApproveCount() * 100.0 / total).setScale(1, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return VoteResultsResponse.MotionResultResponse.builder()
                            .id(m.getId())
                            .motionNumber(m.getMotionNumber())
                            .title(m.getTitle())
                            .requiredApproval(m.getRequiredApproval().name())
                            .result(m.getResult() != null ? m.getResult().name() : null)
                            .approveCount(m.getApproveCount())
                            .rejectCount(m.getRejectCount())
                            .abstainCount(m.getAbstainCount())
                            .approveRate(approveRate)
                            .totalVotes(total)
                            .build();
                }).toList();

        BigDecimal participationRate = session.getEligibleCount() > 0
                ? BigDecimal.valueOf((votedCount + delegatedCount) * 100.0 / session.getEligibleCount())
                        .setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        long finalNotResponded = notResponded;
        return VoteResultsResponse.builder()
                .sessionId(id)
                .status(session.getStatus().name())
                .quorumStatus(quorumStatus)
                .motions(motionResults)
                .summary(VoteResultsResponse.SummaryResponse.builder()
                        .totalEligible(session.getEligibleCount())
                        .totalVoted(votedCount)
                        .totalDelegated(delegatedCount)
                        .totalNotResponded(finalNotResponded)
                        .participationRate(participationRate)
                        .build())
                .build();
    }

    /**
     * リマインド送信する。
     */
    @Transactional
    public RemindResponse remind(Long id) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        // TODO: 未投票・未委任メンバーへの通知送信、レートリミットチェック
        long votedCount = voteRepository.countDistinctVotersBySessionId(id);
        long delegatedCount = delegationRepository.countBySessionIdAndStatus(id, DelegationStatus.ACCEPTED);
        long notResponded = session.getEligibleCount() - votedCount - delegatedCount;
        if (notResponded <= 0) {
            throw new BusinessException(ProxyVoteErrorCode.NO_PENDING_MEMBERS);
        }

        log.info("リマインド送信: sessionId={}, remindedCount={}", id, notResponded);
        return RemindResponse.builder()
                .remindedCount((int) notResponded)
                .sessionId(id)
                .build();
    }

    /**
     * 自分の投票・委任履歴を取得する。
     */
    public Page<SessionListResponse> getMyHistory(Long currentUserId, SessionStatus status, Pageable pageable) {
        // TODO: ステータスフィルタ付きの実装
        Page<ProxyVoteSessionEntity> sessions = sessionRepository.findByUserInvolvement(currentUserId, pageable);
        return sessions.map(s -> toSessionListResponse(s, currentUserId));
    }

    // ---- 議案操作 ----

    /**
     * 議案を追加する。
     */
    @Transactional
    public MotionResponse addMotion(Long sessionId, MotionRequest request) {
        ProxyVoteSessionEntity session = findSessionOrThrow(sessionId);
        if (session.getStatus() != SessionStatus.DRAFT) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_DRAFT);
        }

        long count = motionRepository.countBySessionId(sessionId);
        if (count >= 30) {
            throw new BusinessException(ProxyVoteErrorCode.MOTION_LIMIT_EXCEEDED);
        }

        RequiredApproval approval = request.getRequiredApproval() != null
                ? RequiredApproval.valueOf(request.getRequiredApproval()) : RequiredApproval.MAJORITY;

        ProxyVoteMotionEntity motion = ProxyVoteMotionEntity.builder()
                .sessionId(sessionId)
                .motionNumber((int) count + 1)
                .title(request.getTitle())
                .description(request.getDescription())
                .requiredApproval(approval)
                .build();
        motion = motionRepository.save(motion);
        return mapper.toMotionResponse(motion);
    }

    /**
     * 議案を更新する。
     */
    @Transactional
    public MotionResponse updateMotion(Long motionId, MotionRequest request) {
        ProxyVoteMotionEntity motion = findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = findSessionOrThrow(motion.getSessionId());

        if (session.getStatus() == SessionStatus.DRAFT) {
            RequiredApproval approval = request.getRequiredApproval() != null
                    ? RequiredApproval.valueOf(request.getRequiredApproval()) : motion.getRequiredApproval();
            motion.update(request.getTitle(), request.getDescription(), approval);
        } else if (session.getStatus() == SessionStatus.OPEN) {
            motion.updateWhenOpen(request.getTitle(), request.getDescription());
        } else {
            throw new BusinessException(ProxyVoteErrorCode.SESSION_NOT_UPDATABLE);
        }

        motion = motionRepository.save(motion);
        return mapper.toMotionResponse(motion);
    }

    /**
     * 議案を削除する（DRAFT のみ）。
     */
    @Transactional
    public void deleteMotion(Long motionId) {
        ProxyVoteMotionEntity motion = findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = findSessionOrThrow(motion.getSessionId());
        if (session.getStatus() != SessionStatus.DRAFT) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_DRAFT);
        }
        motionRepository.delete(motion);
    }

    // ---- ヘルパーメソッド ----

    ProxyVoteSessionEntity findSessionOrThrow(Long id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.SESSION_NOT_FOUND));
    }

    ProxyVoteMotionEntity findMotionOrThrow(Long id) {
        return motionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
    }

    private void createMotions(Long sessionId, List<MotionRequest> motionRequests) {
        int number = 1;
        for (MotionRequest mr : motionRequests) {
            RequiredApproval approval = mr.getRequiredApproval() != null
                    ? RequiredApproval.valueOf(mr.getRequiredApproval()) : RequiredApproval.MAJORITY;
            ProxyVoteMotionEntity motion = ProxyVoteMotionEntity.builder()
                    .sessionId(sessionId)
                    .motionNumber(number++)
                    .title(mr.getTitle())
                    .description(mr.getDescription())
                    .requiredApproval(approval)
                    .build();
            motionRepository.save(motion);
        }
    }

    private void validateScopeIds(ProxyVoteScopeType scopeType, Long teamId, Long organizationId) {
        if (scopeType == ProxyVoteScopeType.TEAM && teamId == null) {
            throw new BusinessException(ProxyVoteErrorCode.SCOPE_ID_MISMATCH);
        }
        if (scopeType == ProxyVoteScopeType.ORGANIZATION && organizationId == null) {
            throw new BusinessException(ProxyVoteErrorCode.SCOPE_ID_MISMATCH);
        }
    }

    private void validateQuorumThreshold(QuorumType quorumType, BigDecimal threshold) {
        if (quorumType == QuorumType.CUSTOM) {
            if (threshold == null || threshold.compareTo(BigDecimal.valueOf(0.01)) < 0
                    || threshold.compareTo(BigDecimal.valueOf(100.00)) > 0) {
                throw new BusinessException(ProxyVoteErrorCode.INVALID_QUORUM_THRESHOLD);
            }
        }
    }

    private void validateCastVoteRequest(ProxyVoteSessionEntity session,
                                          List<ProxyVoteMotionEntity> motions,
                                          CastVoteRequest request) {
        if (session.getResolutionMode() == ResolutionMode.WRITTEN) {
            if (request.getVotes().size() != motions.size()) {
                throw new BusinessException(ProxyVoteErrorCode.INCOMPLETE_VOTES);
            }
        }

        for (CastVoteRequest.VoteItem item : request.getVotes()) {
            ProxyVoteMotionEntity motion = motions.stream()
                    .filter(m -> m.getId().equals(item.getMotionId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));

            if (session.getResolutionMode() == ResolutionMode.MEETING) {
                if (motion.getVotingStatus() != VotingStatus.VOTING) {
                    throw new BusinessException(ProxyVoteErrorCode.NON_VOTING_MOTION_INCLUDED);
                }
            }
        }
    }

    MotionResult judgeMotionResult(ProxyVoteMotionEntity motion) {
        int total = motion.getApproveCount() + motion.getRejectCount() + motion.getAbstainCount();
        if (total == 0) {
            return MotionResult.REJECTED;
        }
        return switch (motion.getRequiredApproval()) {
            case MAJORITY -> motion.getApproveCount() > total / 2.0
                    ? MotionResult.APPROVED : MotionResult.REJECTED;
            case TWO_THIRDS -> motion.getApproveCount() >= Math.ceil(total * 2.0 / 3.0)
                    ? MotionResult.APPROVED : MotionResult.REJECTED;
            case UNANIMOUS -> motion.getRejectCount() == 0 && motion.getAbstainCount() == 0
                    ? MotionResult.APPROVED : MotionResult.REJECTED;
        };
    }

    QuorumStatusResponse buildQuorumStatus(ProxyVoteSessionEntity session) {
        long votedCount = voteRepository.countDistinctVotersBySessionId(session.getId());
        long delegatedCount = delegationRepository.countBySessionIdAndStatus(session.getId(), DelegationStatus.ACCEPTED);
        long current = votedCount + delegatedCount;
        long notResponded = session.getEligibleCount() - current;
        if (notResponded < 0) notResponded = 0;

        int required = calculateQuorumRequired(session);
        boolean isMet = current >= required;

        return QuorumStatusResponse.builder()
                .required(required)
                .current((int) current)
                .isMet(isMet)
                .votedCount(votedCount)
                .delegatedCount(delegatedCount)
                .notRespondedCount(notResponded)
                .build();
    }

    private int calculateQuorumRequired(ProxyVoteSessionEntity session) {
        int eligible = session.getEligibleCount();
        return switch (session.getQuorumType()) {
            case MAJORITY -> (int) Math.ceil(eligible / 2.0) + 1;
            case TWO_THIRDS -> (int) Math.ceil(eligible * 2.0 / 3.0);
            case CUSTOM -> session.getQuorumThreshold() != null
                    ? (int) Math.ceil(eligible * session.getQuorumThreshold().doubleValue() / 100.0)
                    : (int) Math.ceil(eligible / 2.0) + 1;
        };
    }

    private SessionResponse toSessionResponse(ProxyVoteSessionEntity session, Long currentUserId) {
        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(session.getId());
        List<ProxyVoteAttachmentEntity> attachments =
                attachmentRepository.findByTargetTypeAndTargetIdOrderBySortOrderAsc(AttachmentTargetType.SESSION, session.getId());

        boolean hasVoted = voteRepository.existsBySessionIdAndUserId(session.getId(), currentUserId);
        boolean hasDelegated = delegationRepository.existsBySessionIdAndDelegatorId(session.getId(), currentUserId);

        return SessionResponse.builder()
                .id(session.getId())
                .scopeType(session.getScopeType().name())
                .teamId(session.getTeamId())
                .organizationId(session.getOrganizationId())
                .resolutionMode(session.getResolutionMode().name())
                .title(session.getTitle())
                .description(session.getDescription())
                .status(session.getStatus().name())
                .meetingDate(session.getMeetingDate())
                .votingStartAt(session.getVotingStartAt())
                .votingEndAt(session.getVotingEndAt())
                .isAnonymous(session.getIsAnonymous())
                .isAutoAcceptDelegation(session.getIsAutoAcceptDelegation())
                .quorumType(session.getQuorumType().name())
                .quorumThreshold(session.getQuorumThreshold())
                .eligibleCount(session.getEligibleCount())
                .quorumStatus(buildQuorumStatus(session))
                .motions(mapper.toMotionResponseList(motions))
                .attachments(mapper.toAttachmentResponseList(attachments))
                .myStatus(MyStatusResponse.builder().hasVoted(hasVoted).hasDelegated(hasDelegated).build())
                .version(session.getVersion())
                .createdBy(session.getCreatedBy())
                .createdAt(session.getCreatedAt())
                .build();
    }

    private SessionListResponse toSessionListResponse(ProxyVoteSessionEntity session, Long currentUserId) {
        long motionCount = motionRepository.countBySessionId(session.getId());
        boolean hasVoted = voteRepository.existsBySessionIdAndUserId(session.getId(), currentUserId);
        boolean hasDelegated = delegationRepository.existsBySessionIdAndDelegatorId(session.getId(), currentUserId);

        return SessionListResponse.builder()
                .id(session.getId())
                .scopeType(session.getScopeType().name())
                .resolutionMode(session.getResolutionMode().name())
                .title(session.getTitle())
                .status(session.getStatus().name())
                .meetingDate(session.getMeetingDate())
                .votingStartAt(session.getVotingStartAt())
                .votingEndAt(session.getVotingEndAt())
                .isAnonymous(session.getIsAnonymous())
                .eligibleCount(session.getEligibleCount())
                .motionCount((int) motionCount)
                .quorumStatus(buildQuorumStatus(session))
                .myStatus(MyStatusResponse.builder().hasVoted(hasVoted).hasDelegated(hasDelegated).build())
                .createdAt(session.getCreatedAt())
                .build();
    }
}
