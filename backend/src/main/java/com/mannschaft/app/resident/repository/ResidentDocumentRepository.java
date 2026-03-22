package com.mannschaft.app.resident.repository;

import com.mannschaft.app.resident.entity.ResidentDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 居住者書類リポジトリ。
 */
public interface ResidentDocumentRepository extends JpaRepository<ResidentDocumentEntity, Long> {

    List<ResidentDocumentEntity> findByResidentIdOrderByCreatedAtDesc(Long residentId);
}
