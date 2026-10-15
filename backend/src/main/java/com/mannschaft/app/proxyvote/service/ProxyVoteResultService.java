package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.MotionResult;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.dto.FinalizeRequest;
import com.mannschaft.app.proxyvote.dto.FinalizeResponse;
import com.mannschaft.app.proxyvote.dto.QuorumStatusResponse;
import com.mannschaft.app.proxyvote.dto.RemindResponse;
import com.mannschaft.app.proxyvote.dto.VoteResultsResponse;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 投票結果の集計・確定・リマインド送信を担うサービス。
 * <p>ProxyVoteSessionService 分割（Phase 5 リファクタ）で切り出した。
 * ロジック・エラーコード・トランザクション境界・ログ表現は元実装と完全同一。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyVoteResultService {

    private final ProxyVoteSessionRepository sessionRepository;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteRepository voteRepository;
    private final ProxyDelegationRepository delegationRepository;
    private final ProxyVoteQuorumCalculator quorumCalculator;

    /**
     * 結果を確定する（CLOSED → FINALIZED）。
     */
    @Transactional
    public FinalizeResponse finalizeSession(Long id, FinalizeRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        if (session.getStatus() != SessionStatus.CLOSED) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_CLOSED);
        }

        QuorumStatusResponse quorumStatus = quorumCalculator.buildQuorumStatus(session);
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
                MotionResult result = quorumCalculator.judgeMotionResult(motion);
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
     * 投票結果を取得する。
     */
    public VoteResultsResponse getResults(Long id) {
        ProxyVoteSessionEntity session = findSessionOrThrow(id);
        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(id);

        long votedCount = voteRepository.countDistinctVotersBySessionId(id);
        long delegatedCount = delegationRepository.countBySessionIdAndStatus(id, DelegationStatus.ACCEPTED);
        long notResponded = session.getEligibleCount() - votedCount - delegatedCount;
        if (notResponded < 0) notResponded = 0;

        QuorumStatusResponse quorumStatus = quorumCalculator.buildQuorumStatus(session);

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

        // 通知送信・レートリミットは NotificationService 連携時に実装予定
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

    private ProxyVoteSessionEntity findSessionOrThrow(Long id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.SESSION_NOT_FOUND));
    }
}
