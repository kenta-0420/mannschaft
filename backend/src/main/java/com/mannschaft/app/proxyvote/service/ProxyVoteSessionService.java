package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.AccessControlService;
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
import com.mannschaft.app.proxyvote.VotingStatus;
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
import com.mannschaft.app.proxyvote.entity.ProxyVoteAttachmentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteAttachmentRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 投票セッションサービス（ファサード）。
 * <p>セッション・議案のCRUD・状態遷移を担当し、投票登録・結果集計・確定処理は
 * 各専門サービス（{@link ProxyVoteCastService} / {@link ProxyVoteResultService}）に委譲する。
 * 公開 API シグネチャは Phase 5 リファクタ前と完全互換。
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
    private final UserRoleRepository userRoleRepository;
    private final ProxyVoteQuorumCalculator quorumCalculator;
    private final ProxyVoteCastService castService;
    private final ProxyVoteResultService resultService;
    private final AccessControlService accessControlService;

    /**
     * 投票セッション一覧を取得する。
     */
    public Page<SessionListResponse> listSessions(ProxyVoteScopeType scopeType, Long teamId,
                                                   Long organizationId, SessionStatus status,
                                                   Long currentUserId, Pageable pageable) {
        // 認可: 対象スコープの会員のみ一覧参照可（越境の一覧漏洩を防止）
        Long scopeId = scopeType == ProxyVoteScopeType.TEAM ? teamId : organizationId;
        accessControlService.checkMembership(currentUserId, scopeId, scopeType.name());

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
        // 認可: セッションスコープの会員のみ詳細参照可（entity 由来スコープで BOLA 防止）
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());
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

        // 認可: 作成先スコープの管理者（ADMIN/DEPUTY_ADMIN）のみセッションを作成可
        Long requestScopeId = scopeType == ProxyVoteScopeType.TEAM ? request.getTeamId() : request.getOrganizationId();
        accessControlService.checkAdminOrAbove(currentUserId, requestScopeId, scopeType.name());

        if (resolutionMode == ResolutionMode.MEETING && request.getMeetingDate() == null) {
            throw new BusinessException(ProxyVoteErrorCode.MEETING_DATE_REQUIRED);
        }

        QuorumType quorumType = request.getQuorumType() != null
                ? QuorumType.valueOf(request.getQuorumType()) : QuorumType.MAJORITY;
        validateQuorumThreshold(quorumType, request.getQuorumThreshold());

        int eligibleCount = (int) resolveEligibleCount(scopeType, request.getTeamId(), request.getOrganizationId());

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
        // 認可: 作成者またはスコープ管理者のみ更新可（entity 由来スコープで BOLA 防止）
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());

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
    public void deleteSession(Long id, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        // 認可: 作成者またはスコープ管理者のみ削除可
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());
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
        // 認可: 作成者またはスコープ管理者のみ受付開始可
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());
        if (session.getStatus() != SessionStatus.DRAFT) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_DRAFT);
        }

        long motionCount = motionRepository.countBySessionId(id);
        if (motionCount == 0) {
            throw new BusinessException(ProxyVoteErrorCode.NO_MOTIONS);
        }

        int eligibleCount = (int) resolveEligibleCount(session.getScopeType(), session.getTeamId(), session.getOrganizationId());
        session.updateEligibleCount(eligibleCount);
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
        // 認可: 作成者またはスコープ管理者のみ締切可
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());
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
     * 結果を確定する（CLOSED → FINALIZED）。{@link ProxyVoteResultService} へ委譲。
     */
    @Transactional
    public FinalizeResponse finalizeSession(Long id, FinalizeRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        // 認可: 作成者またはスコープ管理者のみ結果確定可
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());
        return resultService.finalizeSession(id, request, currentUserId);
    }

    /**
     * 投票する。{@link ProxyVoteCastService} へ委譲。
     */
    @Transactional
    public CastVoteResponse castVote(Long sessionId, CastVoteRequest request, Long currentUserId) {
        return castService.castVote(sessionId, request, currentUserId);
    }

    /**
     * 投票を変更する。{@link ProxyVoteCastService} へ委譲。
     */
    @Transactional
    public CastVoteResponse updateVote(Long sessionId, CastVoteRequest request, Long currentUserId) {
        return castService.updateVote(sessionId, request, currentUserId);
    }

    /**
     * セッションを複製する。
     */
    @Transactional
    public SessionResponse cloneSession(Long id, CloneSessionRequest request, Long currentUserId) {
        ProxyVoteSessionEntity source = findSessionOrThrow(id);
        // 認可: 複製元スコープの管理者のみ複製可（複製先も同一スコープ）
        accessControlService.checkAdminOrAbove(currentUserId, source.resolveScopeId(), source.scopeTypeName());

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
     * 投票結果を取得する。{@link ProxyVoteResultService} へ委譲。
     */
    public VoteResultsResponse getResults(Long id, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        // 認可: セッションスコープの会員のみ結果参照可（entity 由来スコープで BOLA 防止）
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());
        return resultService.getResults(id);
    }

    /**
     * リマインド送信する。{@link ProxyVoteResultService} へ委譲。
     */
    @Transactional
    public RemindResponse remind(Long id, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        // 認可: 作成者またはスコープ管理者のみリマインド送信可
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());
        return resultService.remind(id);
    }

    /**
     * 自分の投票・委任履歴を取得する。
     */
    public Page<SessionListResponse> getMyHistory(Long currentUserId, SessionStatus status, Pageable pageable) {
        // ステータスフィルタは SessionRepository のクエリ拡張時に対応予定
        Page<ProxyVoteSessionEntity> sessions = sessionRepository.findByUserInvolvement(currentUserId, pageable);
        return sessions.map(s -> toSessionListResponse(s, currentUserId));
    }

    // ---- 議案操作 ----

    /**
     * 議案を追加する。
     */
    @Transactional
    public MotionResponse addMotion(Long sessionId, MotionRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(sessionId);
        // 認可: 作成者またはスコープ管理者のみ議案追加可
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());
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
    public MotionResponse updateMotion(Long motionId, MotionRequest request, Long currentUserId) {
        ProxyVoteMotionEntity motion = findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = findSessionOrThrow(motion.getSessionId());
        // 認可: 作成者またはスコープ管理者のみ議案更新可
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());

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
    public void deleteMotion(Long motionId, Long currentUserId) {
        ProxyVoteMotionEntity motion = findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = findSessionOrThrow(motion.getSessionId());
        // 認可: 作成者またはスコープ管理者のみ議案削除可
        accessControlService.checkOwnerOrAdmin(currentUserId, session.getCreatedBy(),
                session.resolveScopeId(), session.scopeTypeName());
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

    /**
     * セッションを Optional で取得する（存在しなくても例外を投げない）。
     *
     * <p>F03.10 代理出席連携（{@link ProxyDelegationService}）で、セッション不在を warning として
     * スキップ判定するために使用する。{@code @SQLRestriction("deleted_at IS NULL")} により
     * 論理削除済みは取得されない。</p>
     */
    public Optional<ProxyVoteSessionEntity> findSessionOptional(Long id) {
        return sessionRepository.findById(id);
    }

    ProxyVoteMotionEntity findMotionOrThrow(Long id) {
        return motionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
    }

    /**
     * 議案の可決/否決判定。{@link ProxyVoteQuorumCalculator} へ委譲。
     * <p>他サービス（{@link ProxyVoteMotionService} 等）からの後方互換のため公開維持。
     */
    MotionResult judgeMotionResult(ProxyVoteMotionEntity motion) {
        return quorumCalculator.judgeMotionResult(motion);
    }

    /**
     * 定足数充足状況の組み立て。{@link ProxyVoteQuorumCalculator} へ委譲。
     */
    QuorumStatusResponse buildQuorumStatus(ProxyVoteSessionEntity session) {
        return quorumCalculator.buildQuorumStatus(session);
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
                .quorumStatus(quorumCalculator.buildQuorumStatus(session))
                .motions(mapper.toMotionResponseList(motions))
                .attachments(mapper.toAttachmentResponseList(attachments))
                .myStatus(MyStatusResponse.builder().hasVoted(hasVoted).hasDelegated(hasDelegated).build())
                .version(session.getVersion())
                .createdBy(session.getCreatedBy())
                .createdAt(session.getCreatedAt())
                .build();
    }

    /**
     * スコープに応じたメンバー数を取得する。
     */
    private long resolveEligibleCount(ProxyVoteScopeType scopeType, Long teamId, Long organizationId) {
        if (scopeType == ProxyVoteScopeType.TEAM && teamId != null) {
            return userRoleRepository.countByTeamId(teamId);
        }
        if (scopeType == ProxyVoteScopeType.ORGANIZATION && organizationId != null) {
            return userRoleRepository.countByOrganizationId(organizationId);
        }
        return 0;
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
                .quorumStatus(quorumCalculator.buildQuorumStatus(session))
                .myStatus(MyStatusResponse.builder().hasVoted(hasVoted).hasDelegated(hasDelegated).build())
                .createdAt(session.getCreatedAt())
                .build();
    }
}
