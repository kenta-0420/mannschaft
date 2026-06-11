package com.mannschaft.app.organization.repository;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.visibility.OrganizationVisibilityProjection;
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
 * 組織リポジトリ。
 */
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, Long> {

    /**
     * カスタムスラッグで組織を取得する（URL識別子）。
     *
     * @param slug URL に使用するカスタムスラッグ
     * @return 対応する組織エンティティ
     */
    Optional<OrganizationEntity> findBySlugAndDeletedAtIsNull(String slug);

    /**
     * 指定スラッグが既に使用中かどうか確認する（一意性チェック用）。
     *
     * @param slug チェック対象のスラッグ
     * @return 使用中の場合 true
     */
    boolean existsBySlugAndDeletedAtIsNull(String slug);

    List<OrganizationEntity> findByVisibility(OrganizationEntity.Visibility visibility);

    boolean existsByName(String name);

    @Query("SELECT o FROM OrganizationEntity o WHERE o.name LIKE %:keyword% OR o.nameKana LIKE %:keyword%")
    Page<OrganizationEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 論理削除済みを含めてIDで検索する（restore用）。
     */
    @Query(value = "SELECT * FROM organizations WHERE id = :id", nativeQuery = true)
    Optional<OrganizationEntity> findByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 論理削除済み組織を復元する。deleted_at を NULL に戻す。
     * @return 更新件数（0 = 対象なし or 削除済みでない）
     */
    @Modifying
    @Query(value = "UPDATE organizations SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreById(@Param("id") Long id);

    /**
     * 論理削除済みを含めた存在確認（restore前の 404 判定用）。
     */
    @Query(value = "SELECT COUNT(*) FROM organizations WHERE id = :id", nativeQuery = true)
    long countByIdIncludingDeleted(@Param("id") Long id);

    // ========================================
    // F01.2 階層表示API用
    // ========================================

    /**
     * 親組織IDのみを軽量に取得する（祖先チェーン構築用）。
     *
     * <p>{@code SQLRestriction("deleted_at IS NULL")} により論理削除済み組織はヒットしない。</p>
     *
     * @param id 対象組織ID
     * @return 親組織ID。トップレベル組織や対象不在の場合は空。
     */
    @Query("SELECT o.parentOrganizationId FROM OrganizationEntity o WHERE o.id = :id")
    Optional<Long> findParentOrganizationIdById(@Param("id") Long id);

    /**
     * 直近の子組織を取得する（{@code parent_organization_id = :parentId} かつ未削除）。
     */
    List<OrganizationEntity> findByParentOrganizationIdAndDeletedAtIsNull(Long parentId, Pageable pageable);

    /**
     * 複数IDを一括取得（祖先チェーンを1回の SQL でまとめて取得する用途）。
     */
    List<OrganizationEntity> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    // ========================================================================
    // F00 ContentVisibilityResolver 基盤拡張 (Phase A-3b)
    //
    // MembershipBatchQueryService.snapshotForUser から §11.6 親 ORG 連鎖チェック
    // のために利用される。設計書 docs/features/F00_content_visibility_resolver.md §11.6 参照。
    // ========================================================================

    /**
     * 指定 ID 集合のうち、非アクティブな組織 ID を返す（§11.6 親 ORG 連鎖判定用）。
     *
     * <p>「非アクティブ」の定義: 現状は {@code deleted_at IS NOT NULL}（論理削除済み）のみ。
     * 将来 {@code SUSPENDED} 列が追加されたら本クエリの WHERE 句に OR 条件を追加すれば
     * 上位の {@link com.mannschaft.app.common.visibility.MembershipBatchQueryService}
     * は無改修で追従する。</p>
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} を回避するため native query を使う。
     * 入力集合が空のときは Spring Data の IN 句で例外となるため呼び出し側でガード必須
     * （{@code MembershipBatchQueryService} 側でガード済み）。</p>
     *
     * @param ids 対象組織 ID 集合（非空）
     * @return 非アクティブな組織 ID のリスト
     */
    @Query(value = "SELECT id FROM organizations WHERE id IN (:ids) AND deleted_at IS NOT NULL",
           nativeQuery = true)
    List<Long> findInactiveIdsByIdIn(@Param("ids") Collection<Long> ids);

    // ========================================================================
    // F00 Phase D-δ: OrganizationVisibilityResolver 用 Projection 一括取得
    //
    // ContentVisibilityChecker.canView(ReferenceType.ORGANIZATION, ...) が
    // OrganizationVisibilityResolver 経由でこのクエリを呼び出す。
    // 設計書: docs/features/F00_content_visibility_resolver.md §4.6 / §7.5。
    // ========================================================================

    /**
     * F00 Phase D-δ: 可視性判定用 Projection を ID 集合で一括取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} が適用された通常のクエリとは異なり、
     * 本クエリでは {@code archivedAt} / {@code deletedAt} を射影することで
     * {@link com.mannschaft.app.common.visibility.ContentStatus} への正規化を Resolver 側で
     * 行えるようにしている。論理削除済行は {@code @SQLRestriction} により通常は除外されるため、
     * {@code deletedAt != null} ケースは主にフラグ不整合の保険として機能する。</p>
     *
     * @param ids 取得対象の組織 ID 集合
     * @return 実存する {@link OrganizationVisibilityProjection} の List
     */
    @Query("SELECT new com.mannschaft.app.organization.visibility.OrganizationVisibilityProjection(" +
           "o.id, o.id, o.visibility, o.archivedAt, o.deletedAt) " +
           "FROM OrganizationEntity o WHERE o.id IN :ids")
    List<OrganizationVisibilityProjection> findVisibilityProjectionsByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * F19.1 Phase 1 Foundation: 未ログイン公開ページ用に PUBLIC 組織を取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ未論理削除・未アーカイブの組織のみ返す。
     * {@code @SQLRestriction("deleted_at IS NULL")} が適用されるため WHERE では
     * 明示的に {@code archivedAt IS NULL} と visibility を絞り込む。</p>
     *
     * <p>F19.1 Phase 2 以降の公開ページ系 Query Service
     * （{@code PublicPostQueryService} 等）から呼ばれる横断利用向け。</p>
     *
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1 / §7.6</p>
     *
     * @param id 対象組織ID
     * @return PUBLIC かつアクティブな組織。条件を満たさない場合は空。
     */
    @Query("SELECT o FROM OrganizationEntity o " +
           "WHERE o.id = :id " +
           "AND o.visibility = com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC " +
           "AND o.archivedAt IS NULL")
    Optional<OrganizationEntity> findPublicOrganizationById(@Param("id") Long id);

    /**
     * F19.1 Phase 3 sitemap.xml 用: PUBLIC かつ未アーカイブの組織を全件取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。</p>
     *
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
     */
    @Query("SELECT o FROM OrganizationEntity o " +
           "WHERE o.visibility = com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC " +
           "AND o.archivedAt IS NULL " +
           "ORDER BY o.id ASC")
    List<OrganizationEntity> findAllPublicOrganizations();

    /**
     * F19.1 Phase 4 公開組織検索: keyword / prefecture でフィルタリングして PUBLIC 組織をページ取得する。
     *
     * <p>認証不要の横断検索のため、{@code AbstractTenantAwareRepository} は継承しない
     * （CLAUDE.md アーキテクチャ原則 7 の「公開横断検索」例外）。</p>
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。
     * {@code archivedAt IS NULL} を明示的に追加して archived も除外する。</p>
     *
     * @param keyword    組織名・説明の部分一致キーワード（null の場合は絞り込みなし）
     * @param prefecture 都道府県名の完全一致（null の場合は絞り込みなし）
     * @param pageable   ページング情報
     * @return PUBLIC かつアクティブな組織のページ
     */
    @Query("""
            SELECT o FROM OrganizationEntity o
            WHERE o.visibility = com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC
              AND o.archivedAt IS NULL
              AND (:keyword IS NULL OR o.name LIKE %:keyword% OR o.nameKana LIKE %:keyword%)
              AND (:prefecture IS NULL OR o.prefecture = :prefecture)
            """)
    Page<OrganizationEntity> searchPublicOrganizations(
            @Param("keyword") String keyword,
            @Param("prefecture") String prefecture,
            Pageable pageable);

    // ========================================================================
    // F19.1 Phase 5: supporter_name_disclosure メトリクス計算用
    // ========================================================================

    /**
     * F19.1 Phase 5: PUBLIC かつ未削除組織のうち REAL_NAME モード有効の件数を返す。
     *
     * <p>Gauge 計算（REAL_NAME 有効率）の分子として使用する。
     * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6</p>
     *
     * @return supporter_name_disclosure = 'REAL_NAME' かつ visibility = PUBLIC かつ未削除の件数
     */
    @Query("""
            SELECT COUNT(o) FROM OrganizationEntity o
            WHERE o.visibility = com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC
              AND o.deletedAt IS NULL
              AND o.supporterNameDisclosure
                  = com.mannschaft.app.publicview.enums.NameDisclosureMode.REAL_NAME
            """)
    long countPublicOrganizationsWithRealName();

    /**
     * F19.1 Phase 5: PUBLIC かつ未削除組織の総件数を返す。
     *
     * <p>Gauge 計算（REAL_NAME 有効率）の分母として使用する。</p>
     *
     * @return visibility = PUBLIC かつ未削除の件数
     */
    @Query("""
            SELECT COUNT(o) FROM OrganizationEntity o
            WHERE o.visibility = com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC
              AND o.deletedAt IS NULL
            """)
    long countPublicOrganizations();
}
