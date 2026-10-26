package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.MotionResult;
import com.mannschaft.app.proxyvote.dto.QuorumStatusResponse;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 定足数判定・議案結果判定の共有計算ロジック。
 * <p>ProxyVoteSessionService 分割（Phase 5 リファクタ）に伴い切り出された
 * ピュアな計算ユーティリティ。ロジックは元実装と完全同一。
 */
@Component
@RequiredArgsConstructor
public class ProxyVoteQuorumCalculator {

    private final ProxyVoteRepository voteRepository;
    private final ProxyDelegationRepository delegationRepository;

    /**
     * 定足数の充足状況を組み立てる。
     */
    public QuorumStatusResponse buildQuorumStatus(ProxyVoteSessionEntity session) {
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

    /**
     * 定足数の必要票数を計算する。
     */
    public int calculateQuorumRequired(ProxyVoteSessionEntity session) {
        int eligible = session.getEligibleCount();
        return switch (session.getQuorumType()) {
            case MAJORITY -> (int) Math.ceil(eligible / 2.0) + 1;
            case TWO_THIRDS -> (int) Math.ceil(eligible * 2.0 / 3.0);
            case CUSTOM -> session.getQuorumThreshold() != null
                    ? (int) Math.ceil(eligible * session.getQuorumThreshold().doubleValue() / 100.0)
                    : (int) Math.ceil(eligible / 2.0) + 1;
        };
    }

    /**
     * 議案の可決/否決を判定する。
     */
    public MotionResult judgeMotionResult(ProxyVoteMotionEntity motion) {
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
}
