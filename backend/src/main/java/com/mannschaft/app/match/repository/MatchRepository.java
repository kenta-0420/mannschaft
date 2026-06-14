package com.mannschaft.app.match.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.entity.MatchEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 団体戦の子ボード一覧を親 match ID から取得する（ボード順昇順・01 §B.6 / §C.4 二段アクセス）。
     *
     * <p><b>IDOR 根絶（01 §A.4 / §C.4）</b>: 子ボードは <b>親 match をテナント取得した後</b>に本メソッドで
     * {@code parent_match_id} スコープで引く（子 ID 直引きで親をまたぐ越境を遮断）。論理削除は Entity の
     * {@code @SQLRestriction("deleted_at IS NULL")} で常に除外される（子ボードも matches なので自身の
     * deleted_at を持つ）。親勝ち星集計・子ボード一覧 GET の双方でこの 1 経路に集約する。</p>
     *
     * @param parentMatchId 親（団体戦）match ID（UUIDv7）
     * @return 子ボード一覧（board_number 昇順・無ければ空）
     */
    List<MatchEntity> findByParentMatchIdOrderByBoardNumberAsc(UUID parentMatchId);

    /** 団体戦の子ボード件数（親 match スコープ）。 */
    long countByParentMatchId(UUID parentMatchId);

    /**
     * カレンダー予定（入口④）から既存試合を解決する（04 §G.1a-2）。
     *
     * <p>当該テナント（organization_id）かつ当該チーム（team_id）が主体で、指定の {@code schedule_id} に
     * 紐づく試合を引く。FE は「この予定に紐づく既存 match があれば開く・無ければ作成」を判定するために用いる
     * （同一予定への二重起票防止）。論理削除は Entity の {@code @SQLRestriction("deleted_at IS NULL")} で常に除外される。</p>
     *
     * <p><b>テナント絞り込み（IDOR）</b>: orgId ＋ teamId をパス由来で強制し、帰属外の予定参照を結果に含めない。
     * 1 予定 = 最大 1 match の運用前提（FE が作成前に本メソッドで重複チェックする）だが、データ整合の保険として
     * 最新（kickoff_at 降順 → id 降順）を先頭に返し、{@code findFirst...} で 1 件を確定する。</p>
     *
     * @param organizationId テナント organization_id（パス由来）
     * @param teamId         主体チーム team_id（パス由来）
     * @param scheduleId     カレンダー予定 ID（schedules ドメインへの BIGINT ID 参照）
     * @return 既存試合（無ければ {@link java.util.Optional#empty()}）
     */
    java.util.Optional<MatchEntity> findFirstByOrganizationIdAndTeamIdAndScheduleIdOrderByKickoffAtDescIdDesc(
            Long organizationId, Long teamId, Long scheduleId);

    /**
     * 大会の対戦カード（fixture）から既存試合を解決する（入口①・04 §G.1a-2 / 06 §I.2）。
     *
     * <p>当該テナント（organization_id）かつ当該チーム（team_id）が主体で、指定の {@code tournament_fixture_id}
     * （大会の対戦カード＝既存 {@code tournament_matches.id}・BIGINT）に紐づく試合を引く。FE は大会の対戦表ページで
     * カード押下時に「このカードに紐づく既存 match があれば live を開く・無ければ作成」を判定するために用いる
     * （同一カードへの二重起票防止）。{@code by-schedule}（入口④）と完全対称の解決経路。</p>
     *
     * <p><b>テナント絞り込み（IDOR）</b>: orgId ＋ teamId をパス由来で強制し、帰属外のカード参照を結果に含めない。
     * 論理削除は Entity の {@code @SQLRestriction("deleted_at IS NULL")} で常に除外される。1 fixture = 最大 1 match の
     * 運用前提（FE が作成前に本メソッドで重複チェックする）だが、データ整合の保険として最新
     * （kickoff_at 降順 → id 降順）を先頭に返し {@code findFirst...} で 1 件を確定する。</p>
     *
     * @param organizationId      テナント organization_id（パス由来）
     * @param teamId              主体チーム team_id（パス由来）
     * @param tournamentFixtureId 大会の対戦カード ID（tournament ドメインへの BIGINT ID 参照）
     * @return 既存試合（無ければ {@link java.util.Optional#empty()}）
     */
    java.util.Optional<MatchEntity> findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc(
            Long organizationId, Long teamId, Long tournamentFixtureId);

    /**
     * チーム試合一覧（コレクション GET 用・Phase2C）。
     *
     * <p>当該テナント（organization_id）かつ当該チームが主体（team_id）の試合をページングで取得する。
     * kind / status / 期間（kickoff_at）/ sport で任意絞り込み（いずれも NULL は無効化＝絞り込まない）。
     * 論理削除は {@code @SQLRestriction("deleted_at IS NULL")}（Entity）で常に除外される。</p>
     *
     * <p>kickoff_at 降順（最新試合が先頭・NULL は最後）→ id 降順で安定ソートする。
     * 単一の親テーブルのみを引く単純なページングクエリで N+1 を起こさない（子テーブルは引かない）。</p>
     *
     * @param orgId  テナント organization_id
     * @param teamId 主体チーム team_id
     * @param status 任意 status フィルタ（NULL=全 status）
     * @param kind   任意 kind フィルタ（NULL=全 kind）
     * @param sport  任意 sport フィルタ（NULL=全 sport）
     * @param from   任意 kickoff_at 下限（含む・NULL=下限なし）
     * @param to     任意 kickoff_at 上限（含む・NULL=上限なし）
     * @param pageable ページング
     * @return 該当試合のページ
     */
    @Query("""
            SELECT m FROM MatchEntity m
            WHERE m.organizationId = :orgId
              AND m.teamId = :teamId
              AND (:status IS NULL OR m.status = :status)
              AND (:kind IS NULL OR m.kind = :kind)
              AND (:sport IS NULL OR m.sport = :sport)
              AND (:from IS NULL OR m.kickoffAt >= :from)
              AND (:to IS NULL OR m.kickoffAt <= :to)
            ORDER BY m.kickoffAt DESC, m.id DESC
            """)
    Page<MatchEntity> findTeamMatches(
            @Param("orgId") Long orgId,
            @Param("teamId") Long teamId,
            @Param("status") MatchStatus status,
            @Param("kind") MatchKind kind,
            @Param("sport") Sport sport,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
