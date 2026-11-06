package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchCareWeekBudgetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 本人user IDで検索範囲を固定する。 */
public interface RanchCareWeekBudgetRepository extends JpaRepository<RanchCareWeekBudgetEntity, UUID> {
    Optional<RanchCareWeekBudgetEntity> findByUserIdAndWeekStartsOn(Long userId, LocalDate weekStartsOn);
}
