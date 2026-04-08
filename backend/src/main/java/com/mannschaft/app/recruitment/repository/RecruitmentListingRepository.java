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
}
