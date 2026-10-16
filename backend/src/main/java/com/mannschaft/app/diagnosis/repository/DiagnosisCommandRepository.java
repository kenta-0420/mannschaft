package com.mannschaft.app.diagnosis.repository;

import com.mannschaft.app.diagnosis.entity.DiagnosisCommandEntity;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** DiagnosisCommandの本人所有条件付き永続化窓口。 */
public interface DiagnosisCommandRepository extends JpaRepository<DiagnosisCommandEntity, UUID> {
    Optional<DiagnosisCommandEntity> findByUserIdAndCommandId(Long userId, UUID commandId);
    void deleteByUserId(Long userId);
}
