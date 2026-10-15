package com.mannschaft.app.proxyvote.repository;

import com.mannschaft.app.proxyvote.ProxyVoteScopeType;
import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 投票セッションリポジトリ。
 */
public interface ProxyVoteSessionRepository extends JpaRepository<ProxyVoteSessionEntity, Long> {

    Page<ProxyVoteSessionEntity> findByScopeTypeAndTeamIdOrderByCreatedAtDesc(
            ProxyVoteScopeType scopeType, Long teamId, Pageable pageable);

    Page<ProxyVoteSessionEntity> findByScopeTypeAndOrganizationIdOrderByCreatedAtDesc(
            ProxyVoteScopeType scopeType, Long organizationId, Pageable pageable);

    Page<ProxyVoteSessionEntity> findByScopeTypeAndTeamIdAndStatusOrderByCreatedAtDesc(
            ProxyVoteScopeType scopeType, Long teamId, SessionStatus status, Pageable pageable);

    Page<ProxyVoteSessionEntity> findByScopeTypeAndOrganizationIdAndStatusOrderByCreatedAtDesc(
            ProxyVoteScopeType scopeType, Long organizationId, SessionStatus status, Pageable pageable);

    /**
     * DRAFT → OPEN 自動遷移対象のセッションを取得する。
     */
    List<ProxyVoteSessionEntity> findByStatusAndVotingStartAtLessThanEqualAndVotingStartAtIsNotNull(
            SessionStatus status, LocalDateTime now);

    /**
     * OPEN → CLOSED 自動遷移対象（WRITTEN モードのみ）を取得する。
     */
    List<ProxyVoteSessionEntity> findByStatusAndResolutionModeAndVotingEndAtLessThanEqualAndVotingEndAtIsNotNull(
            SessionStatus status, ResolutionMode resolutionMode, LocalDateTime now);

    /**
     * ユーザーが関わるセッションを取得する。
     */
    @Query("SELECT s FROM ProxyVoteSessionEntity s WHERE s.id IN " +
           "(SELECT v.motionId FROM ProxyVoteEntity v WHERE v.userId = :userId) " +
           "OR s.id IN (SELECT d.sessionId FROM ProxyDelegationEntity d WHERE d.delegatorId = :userId) " +
           "ORDER BY s.createdAt DESC")
    Page<ProxyVoteSessionEntity> findByUserInvolvement(@Param("userId") Long userId, Pageable pageable);
}
