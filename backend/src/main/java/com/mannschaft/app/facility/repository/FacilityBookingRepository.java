package com.mannschaft.app.facility.repository;

import com.mannschaft.app.facility.BookingStatus;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 施設予約リポジトリ。
 */
public interface FacilityBookingRepository extends JpaRepository<FacilityBookingEntity, Long> {

    @Query("SELECT b FROM FacilityBookingEntity b JOIN SharedFacilityEntity f ON b.facilityId = f.id "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId ORDER BY b.bookingDate DESC, b.timeFrom ASC")
    Page<FacilityBookingEntity> findByScopeOrderByBookingDateDesc(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId, Pageable pageable);

    @Query("SELECT b FROM FacilityBookingEntity b JOIN SharedFacilityEntity f ON b.facilityId = f.id "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId AND b.status = :status "
            + "ORDER BY b.bookingDate DESC, b.timeFrom ASC")
    Page<FacilityBookingEntity> findByScopeAndStatusOrderByBookingDateDesc(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId,
            @Param("status") BookingStatus status, Pageable pageable);

    Optional<FacilityBookingEntity> findById(Long id);

    List<FacilityBookingEntity> findByFacilityIdAndBookingDateAndStatusNotIn(
            Long facilityId, LocalDate bookingDate, List<BookingStatus> excludeStatuses);

    @Query("SELECT b FROM FacilityBookingEntity b JOIN SharedFacilityEntity f ON b.facilityId = f.id "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId "
            + "AND b.bookingDate BETWEEN :dateFrom AND :dateTo "
            + "AND b.status NOT IN :excludeStatuses "
            + "ORDER BY b.bookingDate ASC, b.timeFrom ASC")
    List<FacilityBookingEntity> findCalendarBookings(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId,
            @Param("dateFrom") LocalDate dateFrom, @Param("dateTo") LocalDate dateTo,
            @Param("excludeStatuses") List<BookingStatus> excludeStatuses);

    long countByFacilityIdAndBookingDateAndBookedByAndStatusNotIn(
            Long facilityId, LocalDate bookingDate, Long bookedBy, List<BookingStatus> excludeStatuses);

    @Query("SELECT COUNT(b) FROM FacilityBookingEntity b "
            + "WHERE b.bookedBy = :userId AND b.facilityId = :facilityId "
            + "AND YEAR(b.bookingDate) = :year AND MONTH(b.bookingDate) = :month "
            + "AND b.status NOT IN :excludeStatuses")
    long countMonthlyBookings(
            @Param("userId") Long userId, @Param("facilityId") Long facilityId,
            @Param("year") int year, @Param("month") int month,
            @Param("excludeStatuses") List<BookingStatus> excludeStatuses);

    @Query("SELECT b FROM FacilityBookingEntity b "
            + "WHERE b.facilityId = :facilityId AND b.bookingDate = :date "
            + "AND b.status NOT IN :excludeStatuses "
            + "AND ((b.timeFrom < :timeTo AND b.timeTo > :timeFrom))")
    List<FacilityBookingEntity> findOverlapping(
            @Param("facilityId") Long facilityId, @Param("date") LocalDate date,
            @Param("timeFrom") LocalTime timeFrom, @Param("timeTo") LocalTime timeTo,
            @Param("excludeStatuses") List<BookingStatus> excludeStatuses);

    @Query("SELECT COUNT(b) FROM FacilityBookingEntity b JOIN SharedFacilityEntity f ON b.facilityId = f.id "
            + "WHERE f.scopeType = :scopeType AND f.scopeId = :scopeId AND b.status = :status")
    long countByScopeAndStatus(
            @Param("scopeType") String scopeType, @Param("scopeId") Long scopeId,
            @Param("status") BookingStatus status);

    /**
     * 横断検索（グローバル検索）用のキーワード検索。閲覧者の可視スコープに限定する。
     *
     * <p>予約自体はスコープ列を持たないため、施設（{@code SharedFacilityEntity}）のスコープに委ねる。
     * 可視範囲は「自分が予約したもの」「所属チーム／組織が保有する施設の予約」の和集合とする。
     * 予約の {@code purpose}（利用目的）は施設運営上の機微情報であり、施設スコープ外には出さない。</p>
     *
     * <p>呼び出し側は {@code teamIds} / {@code orgIds} が空の場合、{@code IN ()} の発行を避けるため
     * ダミー値（{@code -1L}）で埋めること。</p>
     *
     * @param keyword  検索キーワード
     * @param teamIds  閲覧者が所属するチーム ID 集合（非空・空ならダミー値）
     * @param orgIds   閲覧者が所属する組織 ID 集合（非空・空ならダミー値）
     * @param userId   閲覧者ユーザー ID（予約者一致判定用）
     * @param pageable 取得件数
     * @return 可視スコープ内の検索結果
     */
    @Query("""
            SELECT b FROM FacilityBookingEntity b
            WHERE b.purpose LIKE %:keyword%
              AND (b.bookedBy = :userId
                OR b.facilityId IN (
                    SELECT f.id FROM SharedFacilityEntity f
                    WHERE f.deletedAt IS NULL
                      AND ((f.scopeType = 'TEAM' AND f.scopeId IN :teamIds)
                        OR (f.scopeType = 'ORGANIZATION' AND f.scopeId IN :orgIds))
                ))
            """)
    List<FacilityBookingEntity> searchByKeyword(@Param("keyword") String keyword,
                                                @Param("teamIds") Collection<Long> teamIds,
                                                @Param("orgIds") Collection<Long> orgIds,
                                                @Param("userId") Long userId,
                                                Pageable pageable);
}
