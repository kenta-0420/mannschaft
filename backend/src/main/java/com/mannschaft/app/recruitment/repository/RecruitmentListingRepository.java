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

    Page<RecruitmentListingEntity> findByScopeTypeAndScopeIdOrderByStartAtDesc(
            RecruitmentScopeType scopeType, Long scopeId, Pageable pageable);

    Page<RecruitmentListingEntity> findByScopeTypeAndScopeIdAndStatusOrderByStartAtDesc(
            RecruitmentScopeType scopeType, Long scopeId, RecruitmentListingStatus status, Pageable pageable);

    Optional<RecruitmentListingEntity> findByIdAndScopeTypeAndScopeId(
            Long id, RecruitmentScopeType scopeType, Long scopeId);

    /**
     * F03.11 Phase 4 全体検索クエリ (§9.x)。
     *
     * status = OPEN かつ visibility が PUBLIC / SCOPE_ONLY / SUPPORTERS_ONLY の募集を対象とする。
     * visibility フィルタは検索結果への包含判定のみで、詳細閲覧時に権限チェックを行う。
     * keyword・location は LIKE 検索。null を渡した場合は条件を無視する。
     * startFrom / startTo が null の場合も同様に無視する。
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
            AND (:keyword IS NULL OR r.title LIKE %:keyword% OR r.description LIKE %:keyword%)
            AND (:location IS NULL OR r.location LIKE %:location%)
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
     */
    @Modifying
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
     */
    @Modifying
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
     */
    @Modifying
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
     * 市の公開札一覧を地域×ジャンル×キーワードで検索する。
     *
     * <p>{@code city} 指定時はその市区町村。{@code prefecture} のみ指定時は配下市区町村を
     * ロールアップ（{@code SUBSTRING(city_code,1,2)=:pref} または {@code prefecture_code=:pref}）。
     * {@code includeRegionNone=true} のときは地域未指定（両列 NULL）の札も含める。</p>
     *
     * @param prefecture        都道府県コード（null=全国）
     * @param city              市区町村コード（null=県ロールアップ or 全国）
     * @param categoryId        ジャンル（null=全ジャンル）
     * @param keyword           タイトル部分一致（null=無条件）
     * @param includeRegionNone 地域未指定の札も含めるか
     * @param pageable          ページング
     * @return 公開札ページ
     */
    @Query("""
            SELECT l FROM RecruitmentListingEntity l
            WHERE l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
              AND (:categoryId IS NULL OR l.categoryId = :categoryId)
              AND (:keyword IS NULL OR l.title LIKE %:keyword%)
              AND (
                    (:city IS NOT NULL AND l.cityCode = :city)
                 OR (:city IS NULL AND :prefecture IS NOT NULL
                       AND (l.prefectureCode = :prefecture OR SUBSTRING(l.cityCode, 1, 2) = :prefecture))
                 OR (:city IS NULL AND :prefecture IS NULL)
                 OR (:includeRegionNone = TRUE AND l.prefectureCode IS NULL AND l.cityCode IS NULL)
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
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
            """)
    Optional<RecruitmentListingEntity> findPublicMarketListingById(@Param("id") Long id);

    /**
     * 都道府県ノードごとの公開札件数（市の summary・パンくず用）。
     * city_code の上位 2 桁または prefecture_code で県にロールアップする。
     *
     * @return {@code [prefectureCode, count]} の配列リスト
     */
    @Query("""
            SELECT COALESCE(l.prefectureCode, SUBSTRING(l.cityCode, 1, 2)) AS pref, COUNT(l)
            FROM RecruitmentListingEntity l
            WHERE l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
              AND (l.prefectureCode IS NOT NULL OR l.cityCode IS NOT NULL)
            GROUP BY COALESCE(l.prefectureCode, SUBSTRING(l.cityCode, 1, 2))
            """)
    List<Object[]> countMarketListingsByPrefecture();

    /**
     * 市区町村ノードごとの公開札件数（市の summary 用）。
     *
     * @return {@code [cityCode, count]} の配列リスト
     */
    @Query("""
            SELECT l.cityCode, COUNT(l)
            FROM RecruitmentListingEntity l
            WHERE l.visibility = com.mannschaft.app.recruitment.RecruitmentVisibility.PUBLIC
              AND l.status IN (
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.OPEN,
                  com.mannschaft.app.recruitment.RecruitmentListingStatus.FULL)
              AND l.cityCode IS NOT NULL
            GROUP BY l.cityCode
            """)
    List<Object[]> countMarketListingsByCity();
}
