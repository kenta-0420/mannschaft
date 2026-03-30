package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentStatusHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * インシデントステータス履歴リポジトリ。
 */
public interface IncidentStatusHistoryRepository extends JpaRepository<IncidentStatusHistoryEntity, Long> {

    /**
     * インシデント ID に紐づくステータス履歴を作成日時昇順で取得する。
     */
    List<IncidentStatusHistoryEntity> findByIncidentIdOrderByCreatedAtAsc(Long incidentId);
}
