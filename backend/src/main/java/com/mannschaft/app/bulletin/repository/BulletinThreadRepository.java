package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.visibility.BulletinThreadVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
     * 指定カテゴリ配下の未削除スレッドを一括で未分類（category_id = NULL）に更新する。
     *
     * <p>設計書 F05.1 §5 に従い、カテゴリ削除時に配下スレッドを巻き添え削除せず
     * 「未分類」として残すための処理。論理削除でも物理削除（FK ON DELETE SET NULL）でも
     * スレッド自体は保持される。</p>
     *
     * @param categoryId 削除対象カテゴリ ID
     * @return 未分類化したスレッド件数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BulletinThreadEntity t SET t.categoryId = NULL "
            + "WHERE t.categoryId = :categoryId AND t.deletedAt IS NULL")
    int bulkSetCategoryIdNullByCategoryId(@Param("categoryId") Long categoryId);

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

    // ====================================================================
    // source_type / source_id による関連スレッド検索
    // ====================================================================

    /**
     * ソース種別・ソースIDからスレッドを取得する（アンケート・安否確認等のシステム連携用）。
     *
     * <p>{@code source_type} と {@code source_id} の組み合わせで識別する。
     * 例: {@code source_type="SURVEY"}, {@code source_id=surveyId}</p>
     */
    Optional<BulletinThreadEntity> findBySourceTypeAndSourceIdAndDeletedAtIsNull(String sourceType, Long sourceId);

    // ====================================================================
    // F17.1 Phase 1 — 村スコープ検索 / フィード（B10 担当範囲：読み取り専用）
    // ====================================================================

    /**
     * 村スコープのスレッドを LIKE で部分一致検索する（F17.1 §4.12）。
     *
     * <p>{@code scope_village_id} 一致 + 未削除のみ。
     * FULLTEXT インデックスは title/body 用に既に貼られているが、
     * 村スコープ条件を満たした上で q を解釈する場合は LIKE で十分（Phase 1）。
     * 大量データになった場合は §4.12 で FULLTEXT クエリ化を検討する。</p>
     *
     * <p>Hibernate のディスクリミネータでは {@code scope_village_id} カラムを使う
     * （Entity のフィールド名 {@code scopeVillageId}）。</p>
     */
    @Query("""
            SELECT t FROM BulletinThreadEntity t
            WHERE t.scopeVillageId = :villageId
              AND t.deletedAt IS NULL
              AND (LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(t.body) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY t.createdAt DESC
            """)
    List<BulletinThreadEntity> searchByVillageIdAndKeyword(
            @Param("villageId") UUID villageId,
            @Param("q") String q,
            Pageable pageable);

    /** 村スコープのスレッド検索結果件数（ページャ用）。 */
    @Query("""
            SELECT COUNT(t) FROM BulletinThreadEntity t
            WHERE t.scopeVillageId = :villageId
              AND t.deletedAt IS NULL
              AND (LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(t.body) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    long countByVillageIdAndKeyword(
            @Param("villageId") UUID villageId,
            @Param("q") String q);

    /**
     * 村スコープの最新スレッド N 件を返す（F17.1 §4.13 ダッシュボード集約用）。
     */
    @Query("""
            SELECT t FROM BulletinThreadEntity t
            WHERE t.scopeVillageId = :villageId
              AND t.deletedAt IS NULL
            ORDER BY t.createdAt DESC
            """)
    List<BulletinThreadEntity> findLatestByVillageId(
            @Param("villageId") UUID villageId, Pageable pageable);

    // ====================================================================
    // F17.1 Phase 3-β — 村史月次集計（村ドメインから read-only 呼出）
    // TODO: 将来は VillagePostCreatedEvent によるカウンタ非同期更新へ分離予定。
    // ====================================================================

    /**
     * 村スコープのスレッド件数を期間で集計する。
     *
     * @param villageId 村 ID
     * @param fromInclusive 期間開始（含む）
     * @param toExclusive   期間終了（含まない）
     */
    @Query("""
            SELECT COUNT(t) FROM BulletinThreadEntity t
            WHERE t.scopeVillageId = :villageId
              AND t.deletedAt IS NULL
              AND t.createdAt >= :fromInclusive
              AND t.createdAt <  :toExclusive
            """)
    long countByVillageIdAndCreatedAtBetween(
            @Param("villageId") UUID villageId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("toExclusive") java.time.LocalDateTime toExclusive);

    /**
     * 村スコープのスレッド title を期間内で全件返す（村史 TOP3 トピック抽出用）。
     *
     * <p>件数は通常月数百件程度を想定。万一爆発した場合は将来集計テーブル化する。</p>
     */
    @Query("""
            SELECT t.title FROM BulletinThreadEntity t
            WHERE t.scopeVillageId = :villageId
              AND t.deletedAt IS NULL
              AND t.createdAt >= :fromInclusive
              AND t.createdAt <  :toExclusive
            """)
    List<String> findTitlesByVillageIdAndCreatedAtBetween(
            @Param("villageId") UUID villageId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("toExclusive") java.time.LocalDateTime toExclusive);
}
