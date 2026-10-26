package com.mannschaft.app.proxyvote.repository;

import com.mannschaft.app.proxyvote.VotingStatus;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 議案リポジトリ。
 */
public interface ProxyVoteMotionRepository extends JpaRepository<ProxyVoteMotionEntity, Long> {

    List<ProxyVoteMotionEntity> findBySessionIdOrderByMotionNumberAsc(Long sessionId);

    long countBySessionId(Long sessionId);

    void deleteAllBySessionId(Long sessionId);

    /**
     * 投票タイマー自動終了対象の議案を取得する。
     */
    List<ProxyVoteMotionEntity> findByVotingStatusAndVoteDeadlineAtLessThanEqualAndVoteDeadlineAtIsNotNull(
            VotingStatus votingStatus, LocalDateTime now);

    /**
     * セッション内の全議案が VOTED かどうかを確認する。
     */
    long countBySessionIdAndVotingStatusNot(Long sessionId, VotingStatus votingStatus);

    /**
     * セッション内の PENDING 議案数を取得する。
     */
    long countBySessionIdAndVotingStatus(Long sessionId, VotingStatus votingStatus);
}
