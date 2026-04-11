package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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
}
