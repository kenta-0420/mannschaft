package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatus;
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

    Page<ActivityResultEntity> findByScopeTypeAndScopeIdAndTemplateIdOrderByActivityDateDescIdDesc(
            ActivityScopeType scopeType, Long scopeId, Long templateId, Pageable pageable);

    Optional<ActivityResultEntity> findByScheduleId(Long scheduleId);

    /**
     * ID + visibility + status で活動記録を取得する（スコープ不問・匿名公開経路の正準）。
     *
     * <p><b>本メソッドが匿名公開経路の唯一の入口である。</b>
     * かつて存在した {@code findByIdAndVisibility(Long, ActivityVisibility)} は status 条件を
     * 持たなかったため、{@code visibility=PUBLIC} のまま公開されていない下書き
     * （{@code status=DRAFT}）が匿名で閲覧できてしまう欠陥があった。
     * 同じ穴を再び開けないよう当該メソッドは<b>削除済み</b>であり、
     * status 条件を伴わない ID 直引き finder を本リポジトリに追加してはならない。</p>
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済は自動除外される。</p>
     *
     * @param id 活動記録 ID
     * @param visibility 公開範囲
     * @param status ライフサイクル状態
     * @return 条件を満たす活動記録（存在しない場合は空）
     */
    Optional<ActivityResultEntity> findByIdAndVisibilityAndStatus(
            Long id, ActivityVisibility visibility, ActivityStatus status);

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
    @Query("""
            SELECT new com.mannschaft.app.activity.visibility.ActivityResultVisibilityProjection(
                ar.id,
                CASE
                    WHEN ar.scopeType = com.mannschaft.app.activity.ActivityScopeType.TEAM THEN 'TEAM'
                    WHEN ar.scopeType = com.mannschaft.app.activity.ActivityScopeType.ORGANIZATION THEN 'ORGANIZATION'
                    WHEN ar.scopeType = com.mannschaft.app.activity.ActivityScopeType.COMMITTEE THEN 'COMMITTEE'
                    ELSE NULL
                END,
                ar.scopeId,
                ar.createdBy,
                ar.visibility,
                ar.status)
            FROM ActivityResultEntity ar
            WHERE ar.id IN :ids AND ar.deletedAt IS NULL
            """)
    List<ActivityResultVisibilityProjection> findVisibilityProjectionsByIdIn(
            @Param("ids") Collection<Long> ids);
}
