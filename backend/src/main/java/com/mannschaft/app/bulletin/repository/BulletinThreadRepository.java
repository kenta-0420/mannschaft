package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.visibility.BulletinThreadVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 掲示板スレッドリポジトリ。
 */
public interface BulletinThreadRepository extends JpaRepository<BulletinThreadEntity, Long> {

    String SEARCH_QUERY = "SELECT * FROM bulletin_threads WHERE deleted_at IS NULL AND scope_type = :scopeType AND scope_id = :scopeId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE)";
    String SEARCH_COUNT_QUERY = "SELECT COUNT(*) FROM bulletin_threads WHERE deleted_at IS NULL AND scope_type = :scopeType AND scope_id = :scopeId AND MATCH(title, body) AGAINST(:keyword IN BOOLEAN MODE)";

    /**
     * スコープごとのスレッドをページング取得する（ピン留め優先→更新日時降順）。
     */
    Page<BulletinThreadEntity> findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
            ScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * カテゴリ指定でスレッドをページング取得する。
     */
    Page<BulletinThreadEntity> findByCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
            Long categoryId, Pageable pageable);

    /**
     * IDとスコープでスレッドを取得する。
     */
    Optional<BulletinThreadEntity> findByIdAndScopeTypeAndScopeId(Long id, ScopeType scopeType, Long scopeId);

    /**
     * 全文検索でスレッドを取得する。
     */
    @Query(value = SEARCH_QUERY, countQuery = SEARCH_COUNT_QUERY, nativeQuery = true)
    Page<BulletinThreadEntity> searchByKeyword(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("keyword") String keyword,
            Pageable pageable);

    /**
     * スコープ内のピン留めスレッド一覧を取得する。
     */
    List<BulletinThreadEntity> findByScopeTypeAndScopeIdAndIsPinnedTrueOrderByUpdatedAtDesc(
            ScopeType scopeType, Long scopeId);

    /**
     * カテゴリに属するスレッド数を取得する。
     */
    long countByCategoryId(Long categoryId);

    /**
     * F00 共通可視性基盤 — {@link BulletinThreadVisibilityProjection} を 1 SQL でバルク取得する。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
     * §4.6 / §6.3.2 工程 6 / §12.3.1（visibility 概念新設機能の最小実装）。</p>
     *
     * <p>{@code BulletinThreadEntity} の {@code @SQLRestriction("deleted_at IS NULL")} により
     * 論理削除済の行は自動的に除外されるため、明示の WHERE 句は不要。
     * 本メソッドは Resolver の {@code AbstractContentVisibilityResolver#loadProjections} から
     * のみ呼ばれ、戻り値の順序は保証しない。</p>
     *
     * <p>scopeType は {@link ScopeType} の {@code .name()} 文字列をそのまま返す。
     * {@code "PERSONAL"} は基底クラスの MEMBERS_ONLY 評価でメンバー判定に hit せず
     * fail-closed となる（§12.3.1 最小実装の安全側挙動）。</p>
     *
     * @param ids 取得対象 bulletin_thread_id 集合（空の場合は空 List を返す）
     * @return 実存する bulletin_threads の Projection リスト
     */
    @Query("""
            SELECT new com.mannschaft.app.bulletin.visibility.BulletinThreadVisibilityProjection(
                t.id,
                CASE
                    WHEN t.scopeType = com.mannschaft.app.bulletin.ScopeType.TEAM THEN 'TEAM'
                    WHEN t.scopeType = com.mannschaft.app.bulletin.ScopeType.ORGANIZATION THEN 'ORGANIZATION'
                    WHEN t.scopeType = com.mannschaft.app.bulletin.ScopeType.PERSONAL THEN 'PERSONAL'
                    ELSE NULL
                END,
                t.scopeId,
                t.authorId)
            FROM BulletinThreadEntity t
            WHERE t.id IN :ids AND t.deletedAt IS NULL
            """)
    List<BulletinThreadVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
