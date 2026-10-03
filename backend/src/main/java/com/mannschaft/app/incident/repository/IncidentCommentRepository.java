package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentCommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * インシデントコメントリポジトリ。
 */
public interface IncidentCommentRepository extends JpaRepository<IncidentCommentEntity, Long> {

    /**
     * インシデント ID に紐づく未削除コメントを作成日時昇順で取得する。
     */
    @Query("""
            SELECT c FROM IncidentCommentEntity c
            WHERE c.incidentId = :incidentId
              AND c.deletedAt IS NULL
              AND (:includeInternal = true OR c.isInternal = false)
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<IncidentCommentEntity> findVisibleByIncidentIdOrderByCreatedAtAsc(
            @Param("incidentId") Long incidentId,
            @Param("includeInternal") boolean includeInternal);
}
