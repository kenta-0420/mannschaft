package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * インシデント添付ファイルリポジトリ。
 */
public interface IncidentAttachmentRepository extends JpaRepository<IncidentAttachmentEntity, Long> {

    /**
     * インシデント ID に紐づく添付ファイル一覧を取得する。
     */
    List<IncidentAttachmentEntity> findByIncidentId(Long incidentId);

    /**
     * インシデント ID に紐づく添付ファイル数を返す。
     */
    int countByIncidentId(Long incidentId);
}
