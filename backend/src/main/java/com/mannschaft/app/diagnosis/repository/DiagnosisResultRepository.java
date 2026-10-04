package com.mannschaft.app.diagnosis.repository;

import com.mannschaft.app.diagnosis.entity.DiagnosisResultEntity;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** DiagnosisResultの本人所有条件付き永続化窓口。 */
public interface DiagnosisResultRepository extends JpaRepository<DiagnosisResultEntity, UUID> {
    Optional<DiagnosisResultEntity> findByIdAndUserId(UUID id, Long userId);
    @Query("select r from DiagnosisResultEntity r where r.userId = :userId and (:method is null or r.method = :method) "
            + "and (:before is null or r.completedAt < :before or (r.completedAt = :before and r.id < :id)) "
            + "order by r.completedAt desc, r.id desc")
    List<DiagnosisResultEntity> findOwnedPage(@Param("userId") Long userId, @Param("method") DiagnosisMethod method,
             @Param("before") Instant before, @Param("id") UUID id, Pageable pageable);
    void deleteByUserId(Long userId);
}
