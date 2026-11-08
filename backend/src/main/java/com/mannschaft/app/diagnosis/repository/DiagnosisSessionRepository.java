package com.mannschaft.app.diagnosis.repository;

import com.mannschaft.app.diagnosis.entity.DiagnosisSessionEntity;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.DiagnosisStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** DiagnosisSessionの本人所有条件付き永続化窓口。 */
public interface DiagnosisSessionRepository extends JpaRepository<DiagnosisSessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DiagnosisSessionEntity s where s.id = :id and s.userId = :userId")
    Optional<DiagnosisSessionEntity> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") Long userId);
    Optional<DiagnosisSessionEntity> findByIdAndUserId(UUID id, Long userId);
    Optional<DiagnosisSessionEntity> findFirstByUserIdAndStatusOrderByUpdatedAtDescIdDesc(Long userId, DiagnosisStatus status);
    Optional<DiagnosisSessionEntity> findByResultIdAndUserId(UUID resultId, Long userId);
    void deleteByUserId(Long userId);
}
