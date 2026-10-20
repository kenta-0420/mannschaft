package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchRewardPausePeriodEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** 運営停止は配送時の状態でなくfact発生時刻で照合する。 */
public interface RanchRewardPausePeriodRepository extends JpaRepository<RanchRewardPausePeriodEntity, UUID> {
    @Query("SELECT COUNT(p) > 0 FROM RanchRewardPausePeriodEntity p WHERE p.startsAt <= :occurredAt "
            + "AND (p.endsAt IS NULL OR :occurredAt < p.endsAt)")
    boolean includes(@Param("occurredAt") Instant occurredAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT period FROM RanchRewardPausePeriodEntity period WHERE period.endsAt IS NULL ORDER BY period.startsAt DESC")
    List<RanchRewardPausePeriodEntity> openPeriods(Pageable pageable);
}
