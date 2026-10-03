package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.BirthProfileConfirmationEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** BirthProfileConfirmationの本人所有条件付き永続化窓口。 */
public interface BirthProfileConfirmationRepository extends JpaRepository<BirthProfileConfirmationEntity, UUID> {
    Optional<BirthProfileConfirmationEntity> findByIdAndUserId(UUID id, Long userId);
    void deleteByUserId(Long userId);
}
