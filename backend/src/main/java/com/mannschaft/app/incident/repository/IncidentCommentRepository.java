package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentCommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * インシデントコメントリポジトリ。
 */
public interface IncidentCommentRepository extends JpaRepository<IncidentCommentEntity, Long> {

    /**
     * インシデント ID に紐づく未削除コメントを作成日時昇順で取得する。
     */
    List<IncidentCommentEntity> findByIncidentIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long incidentId);
}
