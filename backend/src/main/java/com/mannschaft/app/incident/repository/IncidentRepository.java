package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * インシデントリポジトリ。
 */
public interface IncidentRepository extends JpaRepository<IncidentEntity, Long> {

    /**
     * ID で未削除のインシデントを取得する。
     */
    Optional<IncidentEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープに紐づく未削除インシデントを作成日時降順で取得する。
     */
    List<IncidentEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String scopeType, Long scopeId);

    /**
     * スコープに紐づく未削除インシデントを、status 絞り込み（任意）付きで DB ページングして取得する
     * （CMP-028 Phase D）。{@code status} が {@code null} の場合は全 status を対象とする。
     * 総件数も本クエリの COUNT 派生クエリで DB が算出する（{@code Page#getTotalElements()}）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（{@code null} なら全件）
     * @param pageable  ページング情報
     * @return インシデントのページ
     */
    @Query(value = """
            SELECT i FROM IncidentEntity i
            WHERE i.scopeType = :scopeType
              AND i.scopeId = :scopeId
              AND i.deletedAt IS NULL
              AND (:status IS NULL OR i.status = :status)
              AND (:canViewAll = true OR i.reportedBy = :userId OR EXISTS (
                  SELECT 1 FROM IncidentAssignmentEntity assignment
                  WHERE assignment.incidentId = i.id
                    AND assignment.userId = :userId
                    AND assignment.assigneeType = 'USER'
              ))
            ORDER BY i.createdAt DESC
            """, countQuery = """
            SELECT COUNT(i) FROM IncidentEntity i
            WHERE i.scopeType = :scopeType
              AND i.scopeId = :scopeId
              AND i.deletedAt IS NULL
              AND (:status IS NULL OR i.status = :status)
              AND (:canViewAll = true OR i.reportedBy = :userId OR EXISTS (
                  SELECT 1 FROM IncidentAssignmentEntity assignment
                  WHERE assignment.incidentId = i.id
                    AND assignment.userId = :userId
                    AND assignment.assigneeType = 'USER'
              ))
            """)
    Page<IncidentEntity> findVisibleByScopeTypeAndScopeIdAndStatus(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("status") String status,
            @Param("userId") Long userId,
            @Param("canViewAll") boolean canViewAll,
            Pageable pageable);

    /**
     * 報告者 ID で未削除インシデントを取得する。
     */
    List<IncidentEntity> findByReportedByAndDeletedAtIsNull(Long reportedBy);
}
