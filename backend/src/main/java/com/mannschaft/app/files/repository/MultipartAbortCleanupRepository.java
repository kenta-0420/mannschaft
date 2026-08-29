package com.mannschaft.app.files.repository;

import com.mannschaft.app.files.entity.MultipartAbortCleanupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface MultipartAbortCleanupRepository extends JpaRepository<MultipartAbortCleanupEntity, UUID> {
    List<MultipartAbortCleanupEntity> findByStatusAndNextAttemptAtBefore(String status, LocalDateTime now);

    @Modifying
    @Transactional
    @Query("update MultipartAbortCleanupEntity c set c.status = 'CLAIMED' where c.id = :id and c.status = 'ABORT_PENDING'")
    int claim(@Param("id") UUID id);
}
