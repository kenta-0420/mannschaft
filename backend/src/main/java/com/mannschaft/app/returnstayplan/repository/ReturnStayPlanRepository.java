package com.mannschaft.app.returnstayplan.repository;

import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** F02.11 予定 Repository の骨格。認可付き検索は Service 実装時に追加する。 */
public interface ReturnStayPlanRepository extends JpaRepository<ReturnStayPlanEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO return_stay_plans
                (id, owner_user_id, plan_type, is_published, country_code,
                 prefecture_code, region_name, timezone, start_date, end_date,
                 version, created_at, updated_at)
            VALUES
                (:id, :ownerUserId, :planType, :published, 'JP',
                 :prefectureCode, NULL, :timezone, :startDate, :endDate,
                 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """, nativeQuery = true)
    int insertNew(
            @Param("id") UUID id,
            @Param("ownerUserId") Long ownerUserId,
            @Param("planType") String planType,
            @Param("published") boolean published,
            @Param("prefectureCode") String prefectureCode,
            @Param("timezone") String timezone,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    Optional<ReturnStayPlanEntity> findByIdAndOwnerUserId(UUID id, Long ownerUserId);

    long countByOwnerUserIdAndEndDateGreaterThanEqual(Long ownerUserId, LocalDate today);

    long countByOwnerUserId(Long ownerUserId);
    Page<ReturnStayPlanEntity> findByOwnerUserId(
            Long ownerUserId, Pageable pageable);

    Page<ReturnStayPlanEntity> findByOwnerUserIdAndEndDateGreaterThanEqual(
            Long ownerUserId, LocalDate today, Pageable pageable);
    void deleteByOwnerUserId(Long ownerUserId);
    List<ReturnStayPlanEntity> findTop500ByEndDateBeforeOrderByEndDateAscIdAsc(LocalDate date);
}
