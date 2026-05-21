package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.visibility.TeamVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * チームリポジトリ。
 */
public interface TeamRepository
        extends JpaRepository<TeamEntity, Long>, JpaSpecificationExecutor<TeamEntity> {

    List<TeamEntity> findByVisibility(TeamEntity.Visibility visibility);

    @Query("SELECT t FROM TeamEntity t WHERE t.name LIKE %:keyword% OR t.nameKana LIKE %:keyword%")
    Page<TeamEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 指定日時点のアクティブチーム数（未削除・未アーカイブ）を取得する（Analytics 集計用）。
     */
    @Query("SELECT COUNT(t) FROM TeamEntity t WHERE t.deletedAt IS NULL AND t.archivedAt IS NULL " +
            "AND t.createdAt <= :endOfDay")
    int countActiveTeamsAsOf(@Param("endOfDay") java.time.LocalDateTime endOfDay);

    /**
     * 広告セグメント用: アクティブなチームをテンプレート・都道府県でフィルタリングする。
     */
    @Query("""
            SELECT t FROM TeamEntity t
            WHERE t.deletedAt IS NULL
              AND t.archivedAt IS NULL
              AND (:template IS NULL OR t.template = :template)
              AND (:prefecture IS NULL OR t.prefecture = :prefecture)
            ORDER BY t.id ASC
            """)
    Page<TeamEntity> findActiveTeamsForSegment(
            @Param("template") String template,
            @Param("prefecture") String prefecture,
            Pageable pageable);

    /**
     * 論理削除済みを含めてIDで検索する（restore用）。
     */
    @Query(value = "SELECT * FROM teams WHERE id = :id", nativeQuery = true)
    Optional<TeamEntity> findByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 論理削除済みチームを復元する。deleted_at を NULL に戻す。
     * @return 更新件数（0 = 対象なし or 削除済みでない）
     */
    @Modifying
    @Query(value = "UPDATE teams SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreById(@Param("id") Long id);

    /**
     * 論理削除済みを含めた存在確認（restore前の 404 判定用）。
     */
    @Query(value = "SELECT COUNT(*) FROM teams WHERE id = :id", nativeQuery = true)
    long countByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 指定テンプレートのアクティブなチーム数を返す（備品ランキング統計用）。
     *
     * @param template チームテンプレート
     * @return チーム数
     */
    @Query("SELECT COUNT(t) FROM TeamEntity t WHERE t.deletedAt IS NULL AND t.archivedAt IS NULL AND t.template = :template")
    long countByTemplate(@Param("template") String template);

    /**
     * F00 Phase D-γ: 可視性判定用 Projection を ID 集合で一括取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} が適用された通常のクエリとは異なり、
     * 本クエリでは {@code archivedAt} / {@code deletedAt} を射影することで
     * {@link com.mannschaft.app.common.visibility.ContentStatus} への正規化を Resolver 側で
     * 行えるようにしている。論理削除済行は {@code @SQLRestriction} により通常は除外されるため、
     * {@code deletedAt != null} ケースは主にフラグ不整合の保険として機能する。</p>
     *
     * @param ids 取得対象のチーム ID 集合
     * @return 実存する {@link TeamVisibilityProjection} の List
     */
    @Query("SELECT new com.mannschaft.app.team.visibility.TeamVisibilityProjection(" +
           "t.id, t.id, t.visibility, t.archivedAt, t.deletedAt) " +
           "FROM TeamEntity t WHERE t.id IN :ids")
    List<TeamVisibilityProjection> findVisibilityProjectionsByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * F15.4 Phase 4: teams.member_count を +1 する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} の影響を受けない nativeQuery を用いて
     * 論理削除済みチームの場合でも安全に no-op となるよう WHERE 句で防御する。
     * イベント駆動の同期更新は best-effort のため、誤差は夜次バッチ（足軽17）で補正する。</p>
     *
     * @param teamId チームID
     * @return 更新件数（0 = 対象なし or 既に論理削除済み）
     */
    @Modifying
    @Query(value = "UPDATE teams SET member_count = member_count + 1 "
            + "WHERE id = :teamId AND deleted_at IS NULL", nativeQuery = true)
    int incrementMemberCount(@Param("teamId") Long teamId);

    /**
     * F15.4 Phase 4: teams.member_count を -1 する（0未満にはしない）。
     *
     * <p>{@code GREATEST(member_count - 1, 0)} で 0 を下回らないよう保護する。
     * イベント駆動の同期更新は best-effort のため、誤差は夜次バッチ（足軽17）で補正する。</p>
     *
     * @param teamId チームID
     * @return 更新件数（0 = 対象なし or 既に論理削除済み）
     */
    @Modifying
    @Query(value = "UPDATE teams SET member_count = GREATEST(member_count - 1, 0) "
            + "WHERE id = :teamId AND deleted_at IS NULL", nativeQuery = true)
    int decrementMemberCount(@Param("teamId") Long teamId);

    /**
     * F15.4 Phase 4: 全 teams の member_count を user_roles から再集計する（夜次バッチ用）。
     *
     * <p>リスナー（足軽16）による同期更新がエラーや @Transactional 境界外で漏れた場合の
     * ドリフト補正を目的とする。論理削除済みの team は更新対象外。
     * 設計書: docs/features/F15.4_team_store_search_within_org.md §3.3 / §11.4</p>
     *
     * @return 更新件数
     */
    @Modifying
    @Query(value = """
            UPDATE teams t
            SET t.member_count = (
                SELECT COUNT(*) FROM user_roles ur WHERE ur.team_id = t.id
            )
            WHERE t.deleted_at IS NULL
            """, nativeQuery = true)
    int recalculateMemberCounts();

    /**
     * F19.1 Phase 1 Foundation: 未ログイン公開ページ用に PUBLIC チームを取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ未論理削除・未アーカイブのチームのみ返す。
     * {@code @SQLRestriction("deleted_at IS NULL")} が適用されるため WHERE では
     * 明示的に {@code archivedAt IS NULL} と visibility を絞り込む。</p>
     *
     * <p>F15.4 Phase 5-β の {@code TeamService.getPublicTeam(Long)} は {@code findById} +
     * 二重 NULL チェックで構成されているが、本メソッドは F19.1 Phase 2 以降の
     * 公開ページ系 Query Service（{@code PublicPostQueryService} 等）から呼ばれる
     * 横断利用向けに Repository 層へ整理して再利用しやすくする。</p>
     *
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1 / §7.6</p>
     *
     * @param id 対象チームID
     * @return PUBLIC かつアクティブなチーム。条件を満たさない場合は空。
     */
    @Query("SELECT t FROM TeamEntity t " +
           "WHERE t.id = :id " +
           "AND t.visibility = com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC " +
           "AND t.archivedAt IS NULL")
    Optional<TeamEntity> findPublicTeamById(@Param("id") Long id);

    /**
     * F19.1 Phase 3 sitemap.xml 用: PUBLIC かつ未アーカイブのチームを全件取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。</p>
     *
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
     */
    @Query("SELECT t FROM TeamEntity t " +
           "WHERE t.visibility = com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC " +
           "AND t.archivedAt IS NULL " +
           "ORDER BY t.id ASC")
    List<TeamEntity> findAllPublicTeams();
}
