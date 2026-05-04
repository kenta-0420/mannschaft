package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.visibility.ActivityResultVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 活動記録リポジトリ。
 */
public interface ActivityResultRepository extends JpaRepository<ActivityResultEntity, Long> {

    Page<ActivityResultEntity> findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
            ActivityScopeType scopeType, Long scopeId, Pageable pageable);

    Page<ActivityResultEntity> findByScopeTypeAndScopeIdAndVisibilityOrderByActivityDateDescIdDesc(
            ActivityScopeType scopeType, Long scopeId, ActivityVisibility visibility, Pageable pageable);

    Page<ActivityResultEntity> findByScopeTypeAndScopeIdAndTemplateIdOrderByActivityDateDescIdDesc(
            ActivityScopeType scopeType, Long scopeId, Long templateId, Pageable pageable);

    Optional<ActivityResultEntity> findByScheduleId(Long scheduleId);

    long countByScopeTypeAndScopeId(ActivityScopeType scopeType, Long scopeId);

    long countByScopeTypeAndScopeIdAndTemplateId(ActivityScopeType scopeType, Long scopeId, Long templateId);

    @Query("SELECT ar FROM ActivityResultEntity ar WHERE ar.scopeType = :scopeType AND ar.scopeId = :scopeId " +
            "AND (:templateId IS NULL OR ar.templateId = :templateId) " +
            "AND (:dateFrom IS NULL OR ar.activityDate >= :dateFrom) " +
            "AND (:dateTo IS NULL OR ar.activityDate <= :dateTo)")
    List<ActivityResultEntity> findForExport(
            @Param("scopeType") ActivityScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("templateId") Long templateId,
            @Param("dateFrom") LocalDate dateFrom,
            @Param("dateTo") LocalDate dateTo,
            Pageable pageable);

    /**
     * F00 ContentVisibilityResolver 向けバッチ射影取得。
     *
     * <p>{@link com.mannschaft.app.activity.visibility.ActivityResultVisibilityResolver}
     * が SQL 1 本で実存確認込みのメタデータ取得を行うために用いる。
     * {@code @SQLRestriction("deleted_at IS NULL")} により論理削除済は自動除外される。</p>
     *
     * <p>{@code scopeType} は enum を文字列として返すため、JPQL では
     * {@code CAST(... AS string)} を用いて {@code "TEAM" / "ORGANIZATION" / "COMMITTEE"}
     * のいずれかにする。</p>
     *
     * @param ids 取得対象 activity_result の ID 集合
     * @return 実存する {@link ActivityResultVisibilityProjection} のリスト（論理削除分を除外）
     */
    @Query("SELECT ar.id AS id, "
            + "CAST(ar.scopeType AS string) AS scopeType, "
            + "ar.scopeId AS scopeId, "
            + "ar.createdBy AS authorUserId, "
            + "ar.visibility AS visibility "
            + "FROM ActivityResultEntity ar WHERE ar.id IN :ids")
    List<ActivityResultVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
