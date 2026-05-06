package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.visibility.TeamVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * チームリポジトリ。
 */
public interface TeamRepository extends JpaRepository<TeamEntity, Long> {

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
}
