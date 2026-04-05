package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.TimelinePollVoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * タイムライン投票投票リポジトリ。
 */
public interface TimelinePollVoteRepository extends JpaRepository<TimelinePollVoteEntity, Long> {

    /**
     * ユーザーの投票を取得する。
     */
    Optional<TimelinePollVoteEntity> findByTimelinePollIdAndUserId(Long timelinePollId, Long userId);

    /**
     * ユーザーが投票済みかを判定する。
     */
    boolean existsByTimelinePollIdAndUserId(Long timelinePollId, Long userId);
}
