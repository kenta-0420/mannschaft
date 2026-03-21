package com.mannschaft.app.proxyvote.repository;

import com.mannschaft.app.proxyvote.entity.ProxyVoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 投票回答リポジトリ。
 */
public interface ProxyVoteRepository extends JpaRepository<ProxyVoteEntity, Long> {

    Optional<ProxyVoteEntity> findByMotionIdAndUserId(Long motionId, Long userId);

    List<ProxyVoteEntity> findByMotionId(Long motionId);

    List<ProxyVoteEntity> findByMotionIdAndIsProxyVoteTrue(Long motionId);

    List<ProxyVoteEntity> findByDelegationId(Long delegationId);

    boolean existsByMotionIdAndUserId(Long motionId, Long userId);

    void deleteByMotionIdAndUserId(Long motionId, Long userId);

    void deleteByDelegationId(Long delegationId);

    /**
     * セッション内でユーザーが投票済みかどうかを確認する。
     */
    @Query("SELECT COUNT(v) > 0 FROM ProxyVoteEntity v JOIN ProxyVoteMotionEntity m ON v.motionId = m.id " +
           "WHERE m.sessionId = :sessionId AND v.userId = :userId AND v.isProxyVote = false")
    boolean existsBySessionIdAndUserId(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /**
     * セッション内の投票者数（自己投票のみ）を取得する。
     */
    @Query("SELECT COUNT(DISTINCT v.userId) FROM ProxyVoteEntity v JOIN ProxyVoteMotionEntity m ON v.motionId = m.id " +
           "WHERE m.sessionId = :sessionId AND v.isProxyVote = false")
    long countDistinctVotersBySessionId(@Param("sessionId") Long sessionId);

    /**
     * セッション内のユーザーの投票一覧を取得する。
     */
    @Query("SELECT v FROM ProxyVoteEntity v JOIN ProxyVoteMotionEntity m ON v.motionId = m.id " +
           "WHERE m.sessionId = :sessionId AND v.userId = :userId")
    List<ProxyVoteEntity> findBySessionIdAndUserId(@Param("sessionId") Long sessionId, @Param("userId") Long userId);
}
