package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchParticipationPeriodEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 本人user IDで検索範囲を固定する。 */
public interface RanchParticipationPeriodRepository extends JpaRepository<RanchParticipationPeriodEntity, UUID> {
    List<RanchParticipationPeriodEntity> findByUserIdAndEndsAtIsNull(Long userId);

    @Query("SELECT COUNT(p) > 0 FROM RanchParticipationPeriodEntity p "
            + "WHERE p.userId = :userId AND p.startsAt <= :occurredAt "
            + "AND (p.endsAt IS NULL OR :occurredAt < p.endsAt)")
    boolean containsActiveAt(@Param("userId") Long userId,
                             @Param("occurredAt") Instant occurredAt);
}
