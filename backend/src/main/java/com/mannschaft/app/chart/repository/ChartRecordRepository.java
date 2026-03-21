package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * カルテレコードリポジトリ。
 */
public interface ChartRecordRepository extends JpaRepository<ChartRecordEntity, Long> {

    /**
     * チーム内のカルテ一覧をページング取得する（ピン留め優先→来店日降順）。
     */
    Page<ChartRecordEntity> findByTeamIdOrderByIsPinnedDescVisitDateDesc(Long teamId, Pageable pageable);

    /**
     * チーム内の特定顧客のカルテ一覧をページング取得する。
     */
    Page<ChartRecordEntity> findByTeamIdAndCustomerUserIdOrderByIsPinnedDescVisitDateDesc(
            Long teamId, Long customerUserId, Pageable pageable);

    /**
     * IDとチームIDでカルテを取得する。
     */
    Optional<ChartRecordEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * 顧客に共有されたカルテ一覧を取得する。
     */
    Page<ChartRecordEntity> findByCustomerUserIdAndIsSharedToCustomerTrueOrderByVisitDateDesc(
            Long customerUserId, Pageable pageable);

    /**
     * 顧客に共有されたカルテを特定チームでフィルタして取得する。
     */
    Page<ChartRecordEntity> findByCustomerUserIdAndTeamIdAndIsSharedToCustomerTrueOrderByVisitDateDesc(
            Long customerUserId, Long teamId, Pageable pageable);

    /**
     * キーワード検索。
     */
    @Query(value = "SELECT * FROM chart_records WHERE deleted_at IS NULL AND team_id = :teamId " +
            "AND (chief_complaint LIKE CONCAT('%', :keyword, '%') " +
            "OR treatment_note LIKE CONCAT('%', :keyword, '%') " +
            "OR next_recommendation LIKE CONCAT('%', :keyword, '%')) " +
            "ORDER BY is_pinned DESC, visit_date DESC",
            countQuery = "SELECT COUNT(*) FROM chart_records WHERE deleted_at IS NULL AND team_id = :teamId " +
                    "AND (chief_complaint LIKE CONCAT('%', :keyword, '%') " +
                    "OR treatment_note LIKE CONCAT('%', :keyword, '%') " +
                    "OR next_recommendation LIKE CONCAT('%', :keyword, '%'))",
            nativeQuery = true)
    Page<ChartRecordEntity> searchByKeyword(@Param("teamId") Long teamId, @Param("keyword") String keyword, Pageable pageable);

    /**
     * 複合条件でカルテを検索する。
     */
    @Query("SELECT c FROM ChartRecordEntity c WHERE c.teamId = :teamId " +
            "AND (:customerUserId IS NULL OR c.customerUserId = :customerUserId) " +
            "AND (:staffUserId IS NULL OR c.staffUserId = :staffUserId) " +
            "AND (:visitDateFrom IS NULL OR c.visitDate >= :visitDateFrom) " +
            "AND (:visitDateTo IS NULL OR c.visitDate <= :visitDateTo) " +
            "AND (:isSharedToCustomer IS NULL OR c.isSharedToCustomer = :isSharedToCustomer) " +
            "ORDER BY c.isPinned DESC, c.visitDate DESC")
    Page<ChartRecordEntity> findByFilters(
            @Param("teamId") Long teamId,
            @Param("customerUserId") Long customerUserId,
            @Param("staffUserId") Long staffUserId,
            @Param("visitDateFrom") LocalDate visitDateFrom,
            @Param("visitDateTo") LocalDate visitDateTo,
            @Param("isSharedToCustomer") Boolean isSharedToCustomer,
            Pageable pageable);

    /**
     * 特定顧客のピン留めカルテ数を取得する。
     */
    long countByTeamIdAndCustomerUserIdAndIsPinnedTrue(Long teamId, Long customerUserId);

    /**
     * 経過グラフ用に特定顧客のカルテIDと来店日を取得する。
     */
    @Query("SELECT c FROM ChartRecordEntity c WHERE c.teamId = :teamId AND c.customerUserId = :customerUserId " +
            "AND (:visitDateFrom IS NULL OR c.visitDate >= :visitDateFrom) " +
            "AND (:visitDateTo IS NULL OR c.visitDate <= :visitDateTo) " +
            "ORDER BY c.visitDate ASC")
    List<ChartRecordEntity> findForProgress(
            @Param("teamId") Long teamId,
            @Param("customerUserId") Long customerUserId,
            @Param("visitDateFrom") LocalDate visitDateFrom,
            @Param("visitDateTo") LocalDate visitDateTo);
}
