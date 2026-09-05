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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
     * カスタムスラッグで組織を取得する（URL識別子。ACTIVE 限定）。
     *
     * <p>柱②-3 検分 P1-2 根治: {@code findBySlugAndDeletedAtIsNull} は PROVISIONED
     * （承諾前の事前作成状態）も返してしまい、{@code resolveOrgId} 経由で公開判定前に
     * PROVISIONED スコープへ到達できてしまう恐れがあった。全ての slug 解決の入口は
     * このメソッドへ差し替え、{@code lifecycleStatus = ACTIVE} を必須条件とする。
     * SYSTEM_ADMIN の管理系・プロビジョニング自身は ID 直参照（{@code findById}）で
     * PROVISIONED 行に到達するため、本メソッドの対象外で影響しない。</p>
     *
     * @param slug URL に使用するカスタムスラッグ
     * @return ACTIVE かつ未削除の組織エンティティ
     */
    Optional<OrganizationEntity> findBySlugAndDeletedAtIsNullAndLifecycleStatus(
            String slug, OrganizationEntity.LifecycleStatus lifecycleStatus);

    /**
     * 指定スラッグが既に使用中かどうか確認する（一意性チェック用）。
     *
     * @param slug チェック対象のスラッグ
     * @return 使用中の場合 true
     */
    boolean existsBySlugAndDeletedAtIsNull(String slug);

    List<OrganizationEntity> findByVisibility(OrganizationEntity.Visibility visibility);

    // existsByName は柱③-A で撤去済み（ORG_002 一律ブロックの残骸。検分P2-6是正）。
    // 同名許可のため、代わりに findActiveByNormalizedName(ForUpdate) を使う。

    /**
     * CMP-260901-1538 柱③-A: 同名確認フロー用の候補検索。
     *
     * <p>検分第4巡是正: {@code TRIM(name) = TRIM(:name) COLLATE utf8mb4_0900_ai_ci} は
     * {@code name} 列に索引が無いため全表走査になっていた。生成列
     * {@code name_trimmed}（{@code GENERATED ALWAYS AS (TRIM(name)) STORED}・索引付き。
     * V201 マイグレーション参照）に対する等価比較へ書き換え、索引を使えるようにする。
     * 照合順序は列自体が {@code utf8mb4_0900_ai_ci}（大文字小文字・アクセントを区別しない）
     * のため明示指定は不要。ACTIVE（{@code lifecycleStatus=ACTIVE}）かつ未削除
     * （{@code @SQLRestriction} により自動除外）のみを対象とする。作成 TX 内で呼ばれることを
     * 想定し、常に最新状態を反映する。</p>
     *
     * @param name 判定対象の名称（未 trim で渡してよい。クエリ側で TRIM する）
     * @return 同名の ACTIVE 組織一覧
     */
    @Query(value = "SELECT * FROM organizations "
            + "WHERE deleted_at IS NULL AND lifecycle_status = 'ACTIVE' "
            + "AND name_trimmed = TRIM(:name)",
            nativeQuery = true)
    List<OrganizationEntity> findActiveByNormalizedName(@Param("name") String name);

    /**
     * CMP-260901-1538 柱③-A 検分P1-2/第4巡是正: {@link #findActiveByNormalizedName} の
     * ロッキングリード版。
     *
     * <p>{@code FOR UPDATE} により InnoDB の REPEATABLE READ スナップショットを無視して
     * <b>最新のコミット済みデータ</b>を読む（呼び出し元がこのクエリより前に他のクエリを
     * 発行しトランザクションのスナップショットが既に確立していても安全）。
     * {@code name_trimmed} の索引を使うことで、{@code FOR UPDATE} が索引レンジロックに
     * 収まり、全表ロック（＝無関係な名称の作成まで巻き込んでブロックする事故）を避ける。
     * {@code DuplicateNameGuardService#checkForCreateAndRun} が行ロック保持中に呼ぶことを
     * 前提とし、同名候補の TOCTOU（確認時点と作成時点の乖離）を防ぐ。</p>
     *
     * @param name 判定対象の名称（未 trim で渡してよい。クエリ側で TRIM する）
     * @return 同名の ACTIVE 組織一覧（最新コミット済み状態）
     */
    @Query(value = "SELECT * FROM organizations "
            + "WHERE deleted_at IS NULL AND lifecycle_status = 'ACTIVE' "
            + "AND name_trimmed = TRIM(:name) FOR UPDATE",
            nativeQuery = true)
    List<OrganizationEntity> findActiveByNormalizedNameForUpdate(@Param("name") String name);

    /**
     * 組織をキーワード検索する（公開検索）。
     *
     * <p>認可根治 Wave6: 結果は <b>PUBLIC かつ未アーカイブ</b>の組織のみに限定する。
     * 未認証でも到達しうる公開検索であり、閲覧者ごとの可視性解決を行わないため、
     * {@code TeamRepository#searchPublicTeams} と同じ「公開スコープのみ返す」流儀に揃える。
     * 論理削除済みは Entity の {@code @SQLRestriction("deleted_at IS NULL")} が除外する。</p>
     *
     * @param keyword  組織名 / カナに対する部分一致キーワード（空文字は全件相当）
     * @param pageable ページング情報
     * @return PUBLIC かつ未アーカイブな組織のページ
     */
    @Query("""
            SELECT o FROM OrganizationEntity o
            WHERE o.visibility = com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC
              AND o.lifecycleStatus = com.mannschaft.app.organization.entity.OrganizationEntity.LifecycleStatus.ACTIVE
              AND o.archivedAt IS NULL
              AND (o.name LIKE %:keyword% OR o.nameKana LIKE %:keyword%)
            """)
    Page<OrganizationEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * TODO スコープ slug 解決用: 指定 ID 集合の id → slug マッピングを一括取得する。
     *
     * <p>TodoResponseConverter が「My TODO」一覧で scopeSlug を充填する際に N+1 を避けるため
     * バッチ取得する。slug のみを SELECT することで SELECT * より軽量。</p>
     *
     * @param ids 取得対象の組織 ID 集合（非空）
     * @return id → slug の Map（存在しない / 論理削除済みは除外）
     */
    @Query("SELECT o.id AS id, o.slug AS slug FROM OrganizationEntity o WHERE o.id IN :ids")
    List<Object[]> findIdAndSlugByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * TODO スコープ slug 解決用: ID → slug の Map を返すデフォルトメソッド。
     *
     * @param ids 取得対象の組織 ID 集合
     * @return id → slug の Map（論理削除済みは @SQLRestriction で自動除外）
     */
    default Map<Long, String> findSlugMapByIdIn(Collection<Long> ids) {
        return findIdAndSlugByIdIn(ids).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (String) row[1]
                ));
    }

    /**
     * マイページ 組織プロジェクト集約用: 指定 ID 集合の id → name（組織名）を一括取得する。
     *
     * <p>{@link #findIdAndSlugByIdIn(Collection)} の name 版。{@code @SQLRestriction("deleted_at IS NULL")}
     * により論理削除済みは自動除外される。</p>
     *
     * @param ids 取得対象の組織 ID 集合（非空）
     * @return id → name の Object[] リスト（[0]=id Long, [1]=name String）
     */
    @Query("SELECT o.id AS id, o.name AS name FROM OrganizationEntity o WHERE o.id IN :ids")
    List<Object[]> findIdAndNameByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * マイページ 組織プロジェクト集約用: ID → name（組織名）の Map を返すデフォルトメソッド。
     *
     * @param ids 取得対象の組織 ID 集合
     * @return id → name の Map（論理削除済みは @SQLRestriction で自動除外）。ids が空なら空 Map
     */
    default Map<Long, String> findNameMapByIdIn(Collection<Long> ids) {
        return findIdAndNameByIdIn(ids).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (String) row[1]
                ));
    }

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
     * F01.2 子組織一覧カーソルページング用: 直近の子組織を「カーソル・可視性・ID 昇順」を
     * すべて SQL 側で解決した上でページ取得する。
     *
     * <p>旧 {@code findByParentOrganizationIdAndDeletedAtIsNull(parentId, pageable)} は
     * カーソル条件を持たず {@code PageRequest.of(0, n)} で常に先頭ページを返すため、
     * 呼び出し側でカーソルをメモリ上フィルタしても DB は毎回同じ行を返し続け
     * 2 ページ目以降が実質空になる欠陥（根治対象）があった。本メソッドはその根治として
     * 以下をすべて SQL に含める:</p>
     * <ul>
     *   <li>{@code cursorId}（{@code o.id > :cursorId}）— カーソルを SQL へ降ろす</li>
     *   <li>可視性（{@code visibility = PUBLIC OR o.id IN :memberOrgIds}）—
     *       呼び出し者が直接所属する組織 ID 集合は
     *       {@link com.mannschaft.app.role.repository.UserRoleRepository#findOrganizationIdsByUserId}
     *       で事前取得して渡す</li>
     *   <li>{@code ORDER BY o.id ASC} — 明示的な順序保証（カーソルの前提）</li>
     * </ul>
     *
     * <p><b>空コレクションの罠</b>: {@code memberOrgIds} が空だと JPQL の {@code IN ()} は
     * 構文エラーになる。呼び出し側（{@code OrganizationHierarchyService}）は所属組織 0 件の
     * 場合、実在しない組織 ID を持たないセンチネル値（{@code -1L}）1件のみを含むリストに
     * 差し替えて渡すこと（PUBLIC 判定はこの条件と OR で独立しているため、所属 0 件でも
     * PUBLIC な子は正しく見える）。</p>
     *
     * <p>呼び出し側は {@code Pageable} で {@code pageSize + 1} 件を要求し、
     * 「戻り件数が {@code pageSize + 1} を満たすか」で {@code hasNext} を判定する
     * （可視性フィルタ後件数ではなく DB 取得件数で判定することで、非公開の子が混じって
     * 可視件数が pageSize 未満になっても偽陰性で打ち切られない）。</p>
     *
     * @param parentId    親組織 ID
     * @param cursorId    カーソル（このID より大きい行のみ取得。null の場合は先頭から）
     * @param memberOrgIds 呼び出し者が直接所属する組織 ID 集合（空不可。0件時はセンチネル必須）
     * @param pageable    ページング情報（{@code pageSize + 1} 件を要求すること）
     * @return カーソル・可視性・ID 昇順をすべて満たす子組織一覧
     */
    @Query("""
            SELECT o FROM OrganizationEntity o
            WHERE o.parentOrganizationId = :parentId
              AND (:cursorId IS NULL OR o.id > :cursorId)
              AND o.lifecycleStatus = com.mannschaft.app.organization.entity.OrganizationEntity.LifecycleStatus.ACTIVE
              AND (o.visibility = com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PUBLIC
                   OR o.id IN :memberOrgIds)
            ORDER BY o.id ASC
            """)
    List<OrganizationEntity> findChildrenPage(
            @Param("parentId") Long parentId,
            @Param("cursorId") Long cursorId,
            @Param("memberOrgIds") Collection<Long> memberOrgIds,
            Pageable pageable);

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
           "AND o.lifecycleStatus = com.mannschaft.app.organization.entity.OrganizationEntity.LifecycleStatus.ACTIVE " +
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
           "AND o.lifecycleStatus = com.mannschaft.app.organization.entity.OrganizationEntity.LifecycleStatus.ACTIVE " +
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
              AND o.lifecycleStatus = com.mannschaft.app.organization.entity.OrganizationEntity.LifecycleStatus.ACTIVE
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
              AND o.lifecycleStatus = com.mannschaft.app.organization.entity.OrganizationEntity.LifecycleStatus.ACTIVE
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
              AND o.lifecycleStatus = com.mannschaft.app.organization.entity.OrganizationEntity.LifecycleStatus.ACTIVE
              AND o.deletedAt IS NULL
            """)
    long countPublicOrganizations();

    /**
     * 指定組織の作成日時（{@code created_at}）を返す。
     *
     * <p>F20.3 ベータ特典の TEAM_ORG {@code membershipTenureDays} メトリクス（スコープ自体の
     * 作成日からの経過日数・設計書 F20.3 02 §2）。scalar（{@code LocalDateTime}）を返すため、
     * 呼び出し側（{@code billing.beta.MembershipQueryService}）は {@code OrganizationEntity} に
     * 依存しない（クロスドメイン Entity 参照 D-1 を回避）。</p>
     */
    @Query("SELECT o.createdAt FROM OrganizationEntity o WHERE o.id = :orgId AND o.deletedAt IS NULL")
    Optional<java.time.LocalDateTime> findCreatedAtById(@Param("orgId") Long orgId);

    /**
     * F20.3 ベータ特典 付与候補 dry-run（設計書 02 §4.5）用: アクティブ（未削除・未アーカイブ）な
     * 組織 ID をページで返す。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。scalar
     * （{@code Long}）を返すため、呼び出し側（{@code billing.beta.BetaPerkCandidateService}）は
     * {@code OrganizationEntity} に依存しない（クロスドメイン Entity 参照 D-1 を回避）。表示名は
     * {@link #findNameMapByIdIn(Collection)} で一括解決する。</p>
     */
    @Query("SELECT o.id FROM OrganizationEntity o WHERE o.archivedAt IS NULL ORDER BY o.id ASC")
    Page<Long> findActiveOrgIdsForBeta(Pageable pageable);
}
