package com.mannschaft.app.match.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.entity.MatchEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 親 {@link MatchEntity} のリポジトリ。
 *
 * <p>テナント（organization_id）スコープを {@link AbstractTenantAwareRepository} で強制する（原則7）。
 * 子テーブル（match_events / player_appearances）への二段アクセスでは、
 * まず本リポジトリの {@code findByIdAndOrganizationIdAndDeletedAtIsNull} で
 * 親をテナント取得することが 1 段目のテナントゲートとなる（01 §A.4）。</p>
 *
 * <p>集計（02 §F）では <b>テナント絞り込みを基底で強制</b>しつつ、N+1 を避けるため
 * 「対象 match 群を 1 クエリで取得 → 子（events/appearances）を matchId IN で一括取得」の
 * 二段読みを用いる（02 §F.3 N+1 回避）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §A.1 / §A.4 / 02 §F</p>
 */
@Repository
public interface MatchRepository extends AbstractTenantAwareRepository<MatchEntity, UUID> {

    /**
     * チーム統計用: 当該テナント・当該チームが主体（team_id）または相手（opponent_team_id）の試合を取得する。
     * 期間（kickoff_at）・kind・sport で任意絞り込み（NULL は無効化＝全件）。
     */
    @Query("""
            SELECT m FROM MatchEntity m
            WHERE m.organizationId = :orgId
              AND (m.teamId = :teamId OR m.opponentTeamId = :teamId)
              AND (:from IS NULL OR m.kickoffAt >= :from)
              AND (:to IS NULL OR m.kickoffAt <= :to)
              AND (:kind IS NULL OR m.kind = :kind)
              AND (:sport IS NULL OR m.sport = :sport)
            ORDER BY m.kickoffAt ASC
            """)
    List<MatchEntity> findForTeamStats(
            @Param("orgId") Long orgId,
            @Param("teamId") Long teamId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("kind") com.mannschaft.app.match.domain.MatchKind kind,
            @Param("sport") com.mannschaft.app.match.domain.Sport sport);

    /**
     * 個人統計用: 当該テナント内で、指定ユーザーが出場記録（player_appearances）を持つ試合を取得する。
     * 期間・kind・sport で任意絞り込み。teamId 指定時は当該チームが関与する試合に限定（他者閲覧の team スコープ）。
     */
    @Query("""
            SELECT m FROM MatchEntity m
            WHERE m.organizationId = :orgId
              AND EXISTS (SELECT 1 FROM PlayerAppearanceEntity pa
                          WHERE pa.matchId = m.id AND pa.playerUserId = :userId)
              AND (:teamId IS NULL OR m.teamId = :teamId OR m.opponentTeamId = :teamId)
              AND (:from IS NULL OR m.kickoffAt >= :from)
              AND (:to IS NULL OR m.kickoffAt <= :to)
              AND (:kind IS NULL OR m.kind = :kind)
              AND (:sport IS NULL OR m.sport = :sport)
            ORDER BY m.kickoffAt ASC
            """)
    List<MatchEntity> findForUserStats(
            @Param("orgId") Long orgId,
            @Param("userId") Long userId,
            @Param("teamId") Long teamId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("kind") com.mannschaft.app.match.domain.MatchKind kind,
            @Param("sport") com.mannschaft.app.match.domain.Sport sport);

    /** 内部利用（COMPLETED 等のフィルタ用にステータスを参照する派生クエリの土台）。 */
    List<MatchEntity> findByOrganizationIdAndStatus(Long organizationId, MatchStatus status);
}
