package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.MotionResult;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.VoteType;
import com.mannschaft.app.proxyvote.VotingStatus;
import com.mannschaft.app.proxyvote.dto.EndVoteResponse;
import com.mannschaft.app.proxyvote.dto.MotionResponse;
import com.mannschaft.app.proxyvote.dto.SessionResponse;
import com.mannschaft.app.proxyvote.dto.StartVoteRequest;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 議案投票制御サービス。MEETING モードの議案別投票開始/終了を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyVoteMotionService {

    private final ProxyVoteSessionService sessionService;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteRepository voteRepository;
    private final ProxyDelegationRepository delegationRepository;
    private final ProxyVoteMapper mapper;

    /**
     * 議案の投票を開始する（MEETING モード。PENDING → VOTING）。
     */
    @Transactional
    public MotionResponse startVote(Long motionId, StartVoteRequest request) {
        ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());

        if (session.getResolutionMode() != ResolutionMode.MEETING) {
            throw new BusinessException(ProxyVoteErrorCode.MEETING_MODE_ONLY);
        }
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }
        if (motion.getVotingStatus() != VotingStatus.PENDING) {
            throw new BusinessException(ProxyVoteErrorCode.MOTION_NOT_PENDING);
        }

        motion.changeVotingStatus(VotingStatus.VOTING);

        if (request != null && request.getDurationSeconds() != null) {
            LocalDateTime deadline = LocalDateTime.now().plusSeconds(request.getDurationSeconds());
            motion.setVoteDeadline(deadline);
        }

        motion = motionRepository.save(motion);
        log.info("議案投票開始: motionId={}, sessionId={}", motionId, session.getId());
        return mapper.toMotionResponse(motion);
    }

    /**
     * 議案の投票を終了する（MEETING モード。VOTING → VOTED）。
     */
    @Transactional
    public EndVoteResponse endVote(Long motionId) {
        ProxyVoteMotionEntity motion = sessionService.findMotionOrThrow(motionId);
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(motion.getSessionId());

        if (session.getResolutionMode() != ResolutionMode.MEETING) {
            throw new BusinessException(ProxyVoteErrorCode.MEETING_MODE_ONLY);
        }
        if (motion.getVotingStatus() != VotingStatus.VOTING) {
            throw new BusinessException(ProxyVoteErrorCode.MOTION_NOT_VOTING);
        }

        // 委任票を加算（代理人の投票内容を委任者に適用）
        List<ProxyDelegationEntity> acceptedDelegations =
                delegationRepository.findBySessionIdAndStatus(session.getId(), DelegationStatus.ACCEPTED);
        for (ProxyDelegationEntity delegation : acceptedDelegations) {
            if (voteRepository.existsByMotionIdAndUserId(motionId, delegation.getDelegatorId())) {
                continue; // 既に代理投票済み or 本人投票済み
            }
            Long delegateId = delegation.getDelegateId();
            if (delegateId != null) {
                // 代理人の投票を取得
                voteRepository.findByMotionIdAndUserId(motionId, delegateId).ifPresent(delegateVote -> {
                    ProxyVoteEntity proxyVote = ProxyVoteEntity.builder()
                            .motionId(motionId)
                            .userId(delegation.getDelegatorId())
                            .voteType(delegateVote.getVoteType())
                            .isProxyVote(true)
                            .delegationId(delegation.getId())
                            .votedAt(LocalDateTime.now())
                            .build();
                    voteRepository.save(proxyVote);
                    motion.incrementVoteCount(delegateVote.getVoteType());
                });
                // 代理人が未投票の場合は棄権扱い
                if (!voteRepository.existsByMotionIdAndUserId(motionId, delegation.getDelegatorId())) {
                    ProxyVoteEntity abstainVote = ProxyVoteEntity.builder()
                            .motionId(motionId)
                            .userId(delegation.getDelegatorId())
                            .voteType(VoteType.ABSTAIN)
                            .isProxyVote(true)
                            .delegationId(delegation.getId())
                            .votedAt(LocalDateTime.now())
                            .build();
                    voteRepository.save(abstainVote);
                    motion.incrementVoteCount(VoteType.ABSTAIN);
                }
            } else {
                // 白紙委任: 棄権扱い
                ProxyVoteEntity abstainVote = ProxyVoteEntity.builder()
                        .motionId(motionId)
                        .userId(delegation.getDelegatorId())
                        .voteType(VoteType.ABSTAIN)
                        .isProxyVote(true)
                        .delegationId(delegation.getId())
                        .votedAt(LocalDateTime.now())
                        .build();
                voteRepository.save(abstainVote);
                motion.incrementVoteCount(VoteType.ABSTAIN);
            }
        }

        // 未投票の出席者は棄権扱い（eligible メンバー一覧からの差分計算は将来対応）

        motion.changeVotingStatus(VotingStatus.VOTED);
        MotionResult result = sessionService.judgeMotionResult(motion);
        motion.setResult(result);
        motionRepository.save(motion);

        int total = motion.getApproveCount() + motion.getRejectCount() + motion.getAbstainCount();
        BigDecimal approveRate = total > 0
                ? BigDecimal.valueOf(motion.getApproveCount() * 100.0 / total).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        log.info("議案投票終了: motionId={}, result={}", motionId, result);
        return EndVoteResponse.builder()
                .motionId(motionId)
                .votingStatus(VotingStatus.VOTED.name())
                .result(result.name())
                .approveCount(motion.getApproveCount())
                .rejectCount(motion.getRejectCount())
                .abstainCount(motion.getAbstainCount())
                .approveRate(approveRate)
                .totalVotes(total)
                .build();
    }

    /**
     * 全議案の一括投票開始（MEETING モード）。
     */
    @Transactional
    public SessionResponse startAllVotes(Long sessionId, Long currentUserId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);

        if (session.getResolutionMode() != ResolutionMode.MEETING) {
            throw new BusinessException(ProxyVoteErrorCode.MEETING_MODE_ONLY);
        }
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        long pendingCount = motionRepository.countBySessionIdAndVotingStatus(sessionId, VotingStatus.PENDING);
        if (pendingCount == 0) {
            throw new BusinessException(ProxyVoteErrorCode.NO_PENDING_MOTIONS);
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(sessionId);
        motions.stream()
                .filter(m -> m.getVotingStatus() == VotingStatus.PENDING)
                .forEach(m -> m.changeVotingStatus(VotingStatus.VOTING));
        motionRepository.saveAll(motions);

        log.info("全議案一括投票開始: sessionId={}", sessionId);
        return sessionService.getSession(sessionId, currentUserId);
    }
}
