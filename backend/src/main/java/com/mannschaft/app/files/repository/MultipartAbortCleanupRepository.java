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
    List<MultipartAbortCleanupEntity> findByStatusAndLeaseUntilBefore(String status, LocalDateTime now);
    List<MultipartAbortCleanupEntity> findByStatusAndLeaseUntilLessThanEqual(String status, LocalDateTime now);
    List<MultipartAbortCleanupEntity> findByStatusAndDeadLetteredAtBefore(String status, LocalDateTime now);

    @Modifying
    @Transactional
    @Query("update MultipartAbortCleanupEntity c set c.status = 'CLAIMED', c.claimedAt = :now, c.leaseUntil = :leaseUntil where c.id = :id and c.status = 'ABORT_PENDING'")
    int claim(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Modifying
    @Transactional
    @Query("update MultipartAbortCleanupEntity c set c.status = 'ABORT_PENDING', c.leaseUntil = null where c.status = 'CLAIMED' and c.leaseUntil <= :now")
    int releaseExpiredClaims(@Param("now") LocalDateTime now);
}
