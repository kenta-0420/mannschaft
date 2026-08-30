package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.visibility.RecruitmentListingVisibilityProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * F03.11 募集型予約: 募集枠リポジトリ。
 *
 * 設計書 §5.2 / §5.3 の楽観的ロック (UPDATE WHERE) と §5.7 編集時の悲観的ロックを提供する。
 */
public interface RecruitmentListingRepository extends JpaRepository<RecruitmentListingEntity, Long> {

    /** @return モデレーションによる非表示を含む募集札の最小通報情報 */
    @Query(value = """
            SELECT id, scope_type AS scopeType, scope_id AS scopeId, created_by AS createdBy, title,
                   visibility, status, moderation_hidden_at AS moderationHiddenAt
            FROM recruitment_listings
            WHERE id = :listingId AND deleted_at IS NULL
            """, nativeQuery = true)
    Optional<ModerationListingProjection> findModerationListingById(@Param("listingId") Long listingId);

    /** モデレーションによる募集札の可逆的な非表示。 */
    @Modifying
    @Query(value = """
            UPDATE recruitment_listings
            SET moderation_hidden_at = CURRENT_TIMESTAMP
            WHERE id = :listingId AND deleted_at IS NULL
            """, nativeQuery = true)
    int hideForModeration(@Param("listingId") Long listingId);

    /** モデレーション非表示の解除。凍結解除だけではこの操作を呼ばない。 */
    @Modifying
    @Query(value = """
            UPDATE recruitment_listings
            SET moderation_hidden_at = NULL
            WHERE id = :listingId AND deleted_at IS NULL
            """, nativeQuery = true)
    int restoreFromModeration(@Param("listingId") Long listingId);

    interface ModerationListingProjection {
        Long getId();
        String getScopeType();
        Long getScopeId();
        Long getCreatedBy();
        String getTitle();
        String getVisibility();
        String getStatus();
        java.time.LocalDateTime getModerationHiddenAt();
    }

    Page<RecruitmentListingEntity> findByScopeTypeAndScopeIdOrderByStartAtDesc(
            RecruitmentScopeType scopeType, Long scopeId, Pageable pageable);

    Page<RecruitmentListingEntity> findByScopeTypeAndScopeIdAndStatusOrderByStartAtDesc(
            RecruitmentScopeType scopeType, Long scopeId, RecruitmentListingStatus status, Pageable pageable);

    Optional<RecruitmentListingEntity> findByIdAndScopeTypeAndScopeIdAndCreatedBy(
            Long id, RecruitmentScopeType scopeType, Long scopeId, Long createdBy);

    @Query("""
            SELECT l FROM RecruitmentListingEntity l
            WHERE l.scopeType = com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
              AND l.scopeId = :userId
              AND l.createdBy = :userId
              AND (:status IS NULL OR l.status = :status)
              AND (:categoryId IS NULL OR l.categoryId = :categoryId)
              AND (:cityCode IS NULL OR EXISTS (
                  SELECT 1 FROM RecruitmentListingRegionEntity rr
                  WHERE rr.listingId = l.id AND rr.cityCode = :cityCode))
              AND (:cityCode IS NOT NULL OR :prefectureCode IS NULL OR EXISTS (
                  SELECT 1 FROM RecruitmentListingRegionEntity rr
                  WHERE rr.listingId = l.id AND rr.prefectureCode = :prefectureCode))
            ORDER BY l.startAt DESC
            """)
    Page<RecruitmentListingEntity> findPersonalMarketListings(
            @Param("userId") Long userId,
            @Param("status") RecruitmentListingStatus status,
            @Param("prefectureCode") String prefectureCode,
            @Param("cityCode") String cityCode,
            @Param("categoryId") Long categoryId,
            Pageable pageable);

    Optional<RecruitmentListingEntity> findByIdAndScopeTypeAndScopeId(
            Long id, RecruitmentScopeType scopeType, Long scopeId);

    /** 個人札の編集・取消用。複合スコープ条件を含めて行ロックし、IDOR と競合を同時に防ぐ。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM RecruitmentListingEntity l WHERE l.id = :id AND l.scopeType = :scopeType AND l.scopeId = :scopeId")
    Optional<RecruitmentListingEntity> findByIdAndScopeTypeAndScopeIdForUpdate(
            @Param("id") Long id,
            @Param("scopeType") RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * F03.11 Phase 4 全体検索クエリ (§9.x)。
     *
     * status = OPEN かつ visibility が PUBLIC / SCOPE_ONLY / SUPPORTERS_ONLY の募集を対象とする。
     * visibility フィルタは検索結果への包含判定のみで、詳細閲覧時に権限チェックを行う。
     * keyword・location は LIKE 検索。null を渡した場合は条件を無視する。
     * startFrom / startTo が null の場合も同様に無視する。
     *
     * <p>keyword・location は呼び出し側（サービス層）で LIKE ワイルドカード（{@code %} / {@code _} / {@code \})
     * をエスケープ済みの前提で受け取る。本クエリは {@code ESCAPE '\'} 句でその
     * エスケープ文字を有効化し、ユーザー入力中の記号をリテラル一致として扱う。</p>
     */
    @Query("""
            SELECT r FROM RecruitmentListingEntity r
            WHERE r.status = 'OPEN'
            AND r.visibility IN ('PUBLIC', 'SCOPE_ONLY', 'SUPPORTERS_ONLY')
            AND (:categoryId IS NULL OR r.categoryId = :categoryId)
            AND (:subcategoryId IS NULL OR r.subcategoryId = :subcategoryId)
            AND (:startFrom IS NULL OR r.startAt >= :startFrom)
            AND (:startTo IS NULL OR r.startAt <= :startTo)
            AND (:participationType IS NULL OR r.participationType = :participationType)
            AND (:keyword IS NULL OR r.title LIKE CONCAT('%', :keyword, '%') ESCAPE '\\' OR r.description LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')
            AND (:location IS NULL OR r.location LIKE CONCAT('%', :location, '%') ESCAPE '\\')
            ORDER BY r.startAt ASC
            """)
    Page<RecruitmentListingEntity> searchPublicListings(
            @Param("categoryId") Long categoryId,
            @Param("subcategoryId") Long subcategoryId,
            @Param("startFrom") LocalDateTime startFrom,
            @Param("startTo") LocalDateTime startTo,
            @Param("participationType") String participationType,
            @Param("keyword") String keyword,
            @Param("location") String location,
            Pageable pageable);

    /**
     * 編集・キャンセル等の書込操作で行ロックを取得する (§5.7)。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM RecruitmentListingEntity l WHERE l.id = :id")
    Optional<RecruitmentListingEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * §5.2 申込確定の楽観的ロック原子操作。
     * 戻り値が 1 → 確定成功 / 0 → 満員 or 不正状態 (キャンセル待ちフローへ)。
     * status 自動遷移 (OPEN → FULL) もこの UPDATE 内で完了する。
     *
     * <p>F22.1 市（最終認証）の根治: native UPDATE 後に一次キャッシュ（永続化コンテキスト）へ
     * 古い OPEN エンティティが残ると、後続の {@code findById} が DB の FULL ではなく古い状態を
     * 返し OPEN→FULL 境界を検知できない。{@code clearAutomatically/flushAutomatically} で
     * UPDATE 前に flush・UPDATE 後にコンテキストをクリアし、{@code findById} が必ず DB 確定状態を
     * 読むことを保証する。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE recruitment_listings
            SET confirmed_count = confirmed_count + 1,
                participant_count_cache = participant_count_cache + 1,
                status = CASE WHEN confirmed_count + 1 >= capacity THEN 'FULL' ELSE status END
            WHERE id = :id
              AND status = 'OPEN'
              AND confirmed_count < capacity
            """, nativeQuery = true)
    int incrementConfirmedAtomic(@Param("id") Long id);

    /**
     * §5.3 キャンセル時の確定数デクリメント。FULL → OPEN 自動復帰込み。
     * 戻り値 1 → 成功 / 0 → 既に 0 件、または存在しない。
     *
     * <p>increment と同様に、status を変える native UPDATE のため一次キャッシュを
     * クリア・flush して後続読み込みが DB 確定状態を見るようにする。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE recruitment_listings
            SET confirmed_count = confirmed_count - 1,
                participant_count_cache = CASE WHEN participant_count_cache > 0 THEN participant_count_cache - 1 ELSE 0 END,
                status = CASE WHEN status = 'FULL' AND confirmed_count - 1 < capacity THEN 'OPEN' ELSE status END
            WHERE id = :id
              AND confirmed_count > 0
            """, nativeQuery = true)
    int decrementConfirmedAtomic(@Param("id") Long id);

    /**
     * §5.2 step8 キャンセル待ち追加の楽観的ロック原子操作。
     * 戻り値 1 → 採番成功 / 0 → 上限超過。
     * 採番後の next_waitlist_position は別途 SELECT で取得する。
     *
     * <p>native UPDATE 後に {@code findById} で next_waitlist_position を再読み込みするため、
     * 一次キャッシュをクリア・flush して DB 確定値を読むようにする。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE recruitment_listings
            SET waitlist_count = waitlist_count + 1,
                next_waitlist_position = next_waitlist_position + 1
            WHERE id = :id
              AND waitlist_count < waitlist_max
            """, nativeQuery = true)
    int incrementWaitlistAtomic(@Param("id") Long id);

    /**
     * §5.6 予約ライン衝突チェック。
     * 同じ予約ライン上で時間帯が重複するキャンセル以外の募集を数える。
     * 重複条件: NOT (endAt <= :startAt OR startAt >= :endAt)
     */
    @Query("""
            SELECT COUNT(r) FROM RecruitmentListingEntity r
            WHERE r.reservationLineId = :lineId
              AND r.status NOT IN (com.mannschaft.app.recruitment.RecruitmentListingStatus.CANCELLED)
              AND r.startAt < :endAt
              AND r.endAt > :startAt
              AND (:excludeId IS NULL OR r.id <> :excludeId)
            """)
    long countOverlappingByLine(
            @Param("lineId") Long lineId,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("excludeId") Long excludeId);

    /**
     * §5.4 自動キャンセルバッチ用: autoCancelAt が現在時刻以前かつ OPEN/FULL 状態の募集を取得。
     * confirmed_count < min_capacity のものが自動キャンセル対象。
     */
    @Query("""
            SELECT l FROM RecruitmentListingEntity l
            WHERE l.autoCancelAt <= :now
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL
              )
              AND l.scopeType IN (
                  com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM,
                  com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION
              )
              AND l.confirmedCount < l.minCapacity
            ORDER BY l.autoCancelAt ASC
            """)
    List<RecruitmentListingEntity> findAutoCancelTargets(@Param("now") LocalDateTime now);

    /**
     * Phase 2 getMyFeed: フォロー先・サポーター先スコープの最新 OPEN 募集を取得する。
     * scope_id が :scopeIds に含まれ、visibility = 'PUBLIC' または 'SCOPE_ONLY' / 'SUPPORTERS_ONLY' のものを返す。
     *
     * @param scopeIds  フォロー・サポーター先の scopeId リスト
     * @param pageable  ページング (通常 size=20)
     */
    @Query("""
            SELECT l FROM RecruitmentListingEntity l
            WHERE l.scopeId IN :scopeIds
              AND l.status = 'OPEN'
              AND l.scopeType IN (
                  com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM,
                  com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION
              )
            ORDER BY l.createdAt DESC
            """)
    List<RecruitmentListingEntity> findOpenByScopeIds(
            @Param("scopeIds") List<Long> scopeIds, Pageable pageable);

    /**
     * F00 共通可視性基盤用の軽量射影取得。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。
     * {@link AbstractContentVisibilityResolver} のテンプレートが本メソッドを 1 回だけ
     * 呼び、実存確認込みで {@link RecruitmentListingVisibilityProjection} の List を
     * 取得する（取得できなかった ID は不存在 or 論理削除として fail-closed 扱い）。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} はネイティブ SQL の WHERE 句
     * には自動付与されないため、JPQL で明示的に除外する。
     */
    @Query("""
            SELECT new com.mannschaft.app.recruitment.visibility.RecruitmentListingVisibilityProjection(
                r.id,
                CASE
                    WHEN r.scopeType = com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM THEN 'TEAM'
                    WHEN r.scopeType = com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION THEN 'ORGANIZATION'
                    WHEN r.scopeType = com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL THEN 'PERSONAL'
                    ELSE NULL
                END,
                r.scopeId,
                r.createdBy,
                r.visibilityTemplateId,
                r.status,
                r.visibility)
            FROM RecruitmentListingEntity r
            WHERE r.id IN :ids AND r.deletedAt IS NULL
            """)
    List<RecruitmentListingVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);

    // ===========================================
    // F22.1 市（Market）公開ビュー検索（02_api_design §3）
    // 固定条件: visibility='PUBLIC' AND status IN (OPEN,FULL) AND deleted_at IS NULL
    //   ※ @SQLRestriction("deleted_at IS NULL") により JPQL は自動で削除済を除外する。
    // ===========================================

    /**
     * 市の公開札一覧を地域×ジャンル×キーワードで検索する（F22.1 Phase2 D・複数地域 N:N 対応）。
     *
     * <p>地域条件は中間表 {@code recruitment_listing_regions}（N:N）に対する {@code EXISTS} で評価する。
     * これにより 1 札が複数地域に紐づいていても <strong>重複行が出ず</strong>、{@code Page} の件数
     * （total）と内容がページングで整合する（DISTINCT による件数ずれ回避）。</p>
     *
     * <ul>
     *   <li>{@code city} 指定 → 当該市区町村に紐づく地域行が EXISTS する札</li>
     *   <li>{@code prefecture} のみ指定 → 当該都道府県に紐づく地域行（県単位 / 配下市区町村いずれも
     *       {@code prefecture_code=:prefecture}）が EXISTS する札</li>
     *   <li>両 NULL → 地域条件なし（全国）</li>
     *   <li>{@code includeRegionNone=true} → 地域行を一切持たない札（{@code NOT EXISTS}）も含める</li>
     * </ul>
     *
     * <p>keyword は呼び出し側（{@code MarketQueryService#searchListings}）で LIKE ワイルドカード
     * （{@code %} / {@code _} / {@code \}）をエスケープ済みの前提で受け取る。本クエリは {@code ESCAPE '\'}
     * 句でそのエスケープ文字を有効化し、ユーザー入力中の記号をリテラル一致として扱う。</p>
     *
     * @param prefecture        都道府県コード（null=全国）
     * @param city              市区町村コード（null=県ロールアップ or 全国）
     * @param categoryId        ジャンル（null=全ジャンル）
     * @param keyword           タイトル部分一致（null=無条件・ワイルドカードはエスケープ済）
     * @param includeRegionNone 地域未設定（中間表 0 件）の札も含めるか
     * @param pageable          ページング
     * @return 公開札ページ（地域重複なし）
     */
    @Query("""
            SELECT l FROM RecruitmentListingEntity l
            WHERE l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
              AND l.scopeType IN (com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL)
              AND (l.scopeType <> com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                   OR EXISTS (
                       SELECT 1 FROM UserEntity u
                       WHERE u.id = l.scopeId
                         AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE
                         AND u.publicProfileEnabled = TRUE))
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
              AND (:categoryId IS NULL OR l.categoryId = :categoryId)
              AND (:keyword IS NULL OR l.title LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')
              AND (
                    (:city IS NOT NULL AND EXISTS (
                        SELECT 1 FROM RecruitmentListingRegionEntity rr
                        WHERE rr.listingId = l.id AND rr.cityCode = :city))
                 OR (:city IS NULL AND :prefecture IS NOT NULL AND EXISTS (
                        SELECT 1 FROM RecruitmentListingRegionEntity rr
                        WHERE rr.listingId = l.id AND rr.prefectureCode = :prefecture))
                 OR (:city IS NULL AND :prefecture IS NULL)
                 OR (:includeRegionNone = TRUE AND NOT EXISTS (
                        SELECT 1 FROM RecruitmentListingRegionEntity rr
                        WHERE rr.listingId = l.id))
              )
            ORDER BY l.startAt ASC
            """)
    Page<RecruitmentListingEntity> searchMarketListings(
            @Param("prefecture") String prefecture,
            @Param("city") String city,
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword,
            @Param("includeRegionNone") boolean includeRegionNone,
            Pageable pageable);

    /** 認証済み閲覧者向け: PUBLIC と、現在も選択公開先を共有する PERSONAL 札を検索する。 */
    @Query("""
            SELECT l FROM RecruitmentListingEntity l
            WHERE (l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
                    OR (l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.SELECTED_SCOPES
                        AND l.scopeType = com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                        AND l.id IN :selectedListingIds))
              AND l.scopeType IN (com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL)
              AND (l.scopeType <> com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                   OR EXISTS (
                       SELECT 1 FROM UserEntity u
                       WHERE u.id = l.scopeId
                         AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE
                         AND (l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.SELECTED_SCOPES
                              OR u.publicProfileEnabled = TRUE)))
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
              AND (:categoryId IS NULL OR l.categoryId = :categoryId)
              AND (:keyword IS NULL OR l.title LIKE CONCAT('%', :keyword, '%') ESCAPE '\\')
              AND (
                    (:city IS NOT NULL AND EXISTS (
                        SELECT 1 FROM RecruitmentListingRegionEntity rr
                        WHERE rr.listingId = l.id AND rr.cityCode = :city))
                 OR (:city IS NULL AND :prefecture IS NOT NULL AND EXISTS (
                        SELECT 1 FROM RecruitmentListingRegionEntity rr
                        WHERE rr.listingId = l.id AND rr.prefectureCode = :prefecture))
                 OR (:city IS NULL AND :prefecture IS NULL)
                 OR (:includeRegionNone = TRUE AND NOT EXISTS (
                        SELECT 1 FROM RecruitmentListingRegionEntity rr
                        WHERE rr.listingId = l.id))
              )
            ORDER BY l.startAt ASC
            """)
    Page<RecruitmentListingEntity> searchAccessibleMarketListings(
            @Param("selectedListingIds") Collection<Long> selectedListingIds,
            @Param("prefecture") String prefecture,
            @Param("city") String city,
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword,
            @Param("includeRegionNone") boolean includeRegionNone,
            Pageable pageable);

    /**
     * 市の公開札を ID で取得する（PUBLIC かつ OPEN/FULL のみ）。
     * 非公開・不在は空（呼び出し側で 404 存在秘匿）。
     *
     * @param id 札ID
     * @return 公開札（非公開・不在は空）
     */
    @Query("""
            SELECT l FROM RecruitmentListingEntity l
            WHERE l.id = :id
              AND l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
              AND l.scopeType IN (com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL)
              AND (l.scopeType <> com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                   OR EXISTS (
                       SELECT 1 FROM UserEntity u
                       WHERE u.id = l.scopeId
                         AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE
                         AND u.publicProfileEnabled = TRUE))
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
            """)
    Optional<RecruitmentListingEntity> findPublicMarketListingById(@Param("id") Long id);

    /** PUBLIC または閲覧者が選択公開先に現在所属する札を、存在秘匿条件付きで取得する。 */
    @Query("""
            SELECT l FROM RecruitmentListingEntity l
            WHERE l.id = :id
              AND (l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
                    OR (l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.SELECTED_SCOPES
                        AND l.scopeType = com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                        AND l.id IN :selectedListingIds))
              AND l.scopeType IN (com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL)
              AND (l.scopeType <> com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                   OR EXISTS (
                       SELECT 1 FROM UserEntity u
                       WHERE u.id = l.scopeId
                         AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE
                         AND (l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.SELECTED_SCOPES
                              OR u.publicProfileEnabled = TRUE)))
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
            """)
    Optional<RecruitmentListingEntity> findAccessibleMarketListingById(
            @Param("id") Long id,
            @Param("selectedListingIds") Collection<Long> selectedListingIds);

    /**
     * 都道府県ノードごとの公開札件数（市の summary・パンくず用・F22.1 Phase2 D 複数地域対応）。
     *
     * <p>中間表 {@code recruitment_listing_regions} を {@code prefecture_code} で GROUP BY し、
     * 公開条件（PUBLIC / OPEN・FULL / 未削除）を満たす札に {@code JOIN} する。</p>
     *
     * <p><strong>件数仕様（県跨ぎ重複計上）</strong>: 「東京＋神奈川」の 1 札は東京・神奈川の双方で
     * +1 として数える（複数地域札は各県に立っているため）。一方、同一県内に 2 市の地域行を持つ札は
     * {@code COUNT(DISTINCT rr.listingId)} により県粒度で 1 件に集約する（同一県の二重計上を防ぐ）。</p>
     *
     * @return {@code [prefectureCode, count]} の配列リスト
     */
    @Query("""
            SELECT rr.prefectureCode, COUNT(DISTINCT rr.listingId)
            FROM RecruitmentListingRegionEntity rr
            JOIN RecruitmentListingEntity l ON l.id = rr.listingId
            WHERE l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
              AND l.scopeType IN (com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL)
              AND (l.scopeType <> com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                   OR EXISTS (
                       SELECT 1 FROM UserEntity u
                       WHERE u.id = l.scopeId
                         AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE
                         AND u.publicProfileEnabled = TRUE))
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
              AND l.deletedAt IS NULL
            GROUP BY rr.prefectureCode
            """)
    List<Object[]> countMarketListingsByPrefecture();

    /**
     * 市区町村ノードごとの公開札件数（市の summary 用・F22.1 Phase2 D 複数地域対応）。
     *
     * <p>中間表 {@code recruitment_listing_regions} の {@code city_code} 非 NULL 行（市区町村単位の地域）を
     * GROUP BY し、公開条件を満たす札に {@code JOIN} する。{@code COUNT(DISTINCT rr.listingId)} で
     * 同一市区町村に対する二重計上を防ぐ。</p>
     *
     * @return {@code [cityCode, count]} の配列リスト
     */
    @Query("""
            SELECT rr.cityCode, COUNT(DISTINCT rr.listingId)
            FROM RecruitmentListingRegionEntity rr
            JOIN RecruitmentListingEntity l ON l.id = rr.listingId
            WHERE l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
              AND l.scopeType IN (com.mannschaft.app.recruitment.RecruitmentScopeType.TEAM,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.ORGANIZATION,
                                  com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL)
              AND (l.scopeType <> com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                   OR EXISTS (
                       SELECT 1 FROM UserEntity u
                       WHERE u.id = l.scopeId
                         AND u.status = com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE
                         AND u.publicProfileEnabled = TRUE))
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
              AND l.deletedAt IS NULL
              AND rr.cityCode IS NOT NULL
            GROUP BY rr.cityCode
            """)
    List<Object[]> countMarketListingsByCity();

    /**
     * <b>論理削除済み（archive 済み）</b>の募集枠のスコープを引く（#2497）。
     *
     * <p><b>なぜネイティブクエリなのか</b>: {@link RecruitmentListingEntity} には
     * {@code @SQLRestriction("deleted_at IS NULL")} が乗っているため、JPQL / 派生クエリ /
     * {@code findById} / {@code existsById} のいずれからも論理削除済みの行には到達できない。
     * ネイティブ SQL だけがこのフィルタを迂回できる。</p>
     *
     * <p><b>何に使うのか</b>: 募集枠が archive 済みだと、NO_SHOW 記録のスコープ帰属クエリ
     * （{@code RecruitmentNoShowRecordRepository#findByIdAndScopeTypeAndScopeId}）が
     * 募集枠を JOIN する都合で引けなくなり、<b>異議の裁定が永久に不能になる</b>。
     * そこで {@code RecruitmentNoShowService#dispute} は申立を受け付けた直後に本クエリで
     * 「裁定不能か」を判定し、不能なら即座に取り下げる。
     * <b>戻り値が存在すること自体が「archive 済み」の信号</b>であり、同時に監査ログへ残す
     * スコープ文脈（team / organization）の唯一の入手経路でもある（1 クエリで両方を満たす）。</p>
     *
     * <p><b>「募集枠の行そのものが存在しない」ケースは扱わない。</b>
     * {@code recruitment_no_show_records.listing_id} には
     * {@code fk_rns_listing ... ON DELETE CASCADE}（V3.128）が張られており、
     * 募集枠の行が物理削除されれば NO_SHOW 記録も道連れに消えるため、
     * 「記録は在るのに募集枠の行が無い」状態は発生しない。</p>
     *
     * <p><b>【訂正 issue #2545】{@code CAST(scope_id AS SIGNED)} は必須ではない。</b>
     * 本 javadoc は当初「本番 DDL（V3.119）の {@code scope_id} は {@code BIGINT UNSIGNED} であり、
     * MySQL Connector/J は符号なし BIGINT を {@code BigInteger} で返すため、射影の
     * {@code Long getScopeId()} に渡すと本番でのみ壊れる」と断定していたが、
     * <b>この機構は実測されていなかった</b>。</p>
     *
     * <p>issue #2545 で Flyway 実スキーマ（＝本番同一の {@code BIGINT UNSIGNED}）上の
     * Testcontainers MySQL に対して実測した結果は次のとおりである
     * （{@code NativeQueryUnsignedBigintTypeIT#符号なしBIGINTの各経路の実行時型を固定する}。
     * 測定条件: MySQL 8.0 + MySQL Connector/J（Spring Boot 3.5 系の管理バージョン）
     * + Hibernate ORM 6.6 系 + Spring Data JPA）:</p>
     * <ul>
     *   <li>生 JDBC {@code ResultSet#getObject} … {@code BigInteger}（ドライバの挙動の記述自体は正しい）</li>
     *   <li>Hibernate ネイティブクエリのスカラ … <b>{@code Long}</b></li>
     *   <li>Spring Data {@code @Query(nativeQuery=true)} の {@code List<Long>} の要素 … <b>{@code Long}</b></li>
     *   <li>射影インタフェースの {@code Long} 宣言 … <b>{@code Long}</b></li>
     *   <li>{@code List<Object[]>} の要素 … <b>{@code Long}</b></li>
     * </ul>
     *
     * <p>Hibernate 6（現行スタックは Spring Boot 3.5 系）はネイティブクエリのスカラ型を
     * {@code ResultSetMetaData#getColumnType}（{@code BIGINT}）で解決し {@code Long} に正規化するため、
     * <b>本測定条件下では</b> {@code BigInteger} が ORM 境界を越えて Java コードに現れることはない
     * （Hibernate 5 系の {@code getColumnClassName} 経由とは挙動が異なる）。
     * よって「テストは通るが本番だけ落ちる」分岐は現行スタックには存在しない。
     * これは無条件の一般則ではなく観測事実であり、
     * ドライバ / Hibernate / Spring Data が入れ替われば上記 IT が赤くなって検知される
     * （#2514 の無条件断定を否定する記述が、同じ形の無条件断定にならないための注記）。</p>
     *
     * <p>それでも CAST を残しているのは、本クエリが {@code l.id = :listingId} による
     * 主キー1行引きであり CAST がインデックス選択に一切影響しないこと、および
     * 「射影が符号付き {@code Long} を期待している」という意図の明示になるためである。
     * 除去も可能だが利得が無いため触らない
     * （インデックス列に CAST が乗って実害が出ていた {@code MyScopeFolderItemRepository} とは事情が異なる）。</p>
     *
     * @param listingId 募集枠 ID
     * @return archive 済みならスコープ、生存中なら空
     */
    @Query(value = """
            SELECT l.scope_type AS scopeType, CAST(l.scope_id AS SIGNED) AS scopeId
            FROM recruitment_listings l
            WHERE l.id = :listingId
              AND l.deleted_at IS NOT NULL
            """, nativeQuery = true)
    Optional<ArchivedListingScope> findArchivedScopeById(@Param("listingId") Long listingId);

    /** {@link #findArchivedScopeById} の射影。 */
    interface ArchivedListingScope {
        /** {@code RecruitmentScopeType} の名前（{@code TEAM} / {@code ORGANIZATION}）。 */
        String getScopeType();

        Long getScopeId();
    }
}
