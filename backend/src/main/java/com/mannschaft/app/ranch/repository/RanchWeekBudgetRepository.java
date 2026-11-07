package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchWeekBudgetEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** 同本人・同UTC週の枠をowner lockの内側で取得する。 */
public interface RanchWeekBudgetRepository extends JpaRepository<RanchWeekBudgetEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM RanchWeekBudgetEntity b WHERE b.userId = :userId AND b.weekStartsOn = :week")
    Optional<RanchWeekBudgetEntity> lockForWeek(@Param("userId") Long userId,
                                                @Param("week") LocalDate week);

    Optional<RanchWeekBudgetEntity> findByUserIdAndWeekStartsOn(Long userId, LocalDate weekStartsOn);
}
