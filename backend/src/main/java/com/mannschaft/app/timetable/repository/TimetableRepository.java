package com.mannschaft.app.timetable.repository;

import com.mannschaft.app.timetable.TimetableStatus;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 時間割リポジトリ。
 */
public interface TimetableRepository extends JpaRepository<TimetableEntity, Long> {

    List<TimetableEntity> findByTeamIdOrderByEffectiveFromDesc(Long teamId);

    Optional<TimetableEntity> findByIdAndTeamId(Long id, Long teamId);

    List<TimetableEntity> findByTeamIdAndStatus(Long teamId, TimetableStatus status);

    /**
     * 指定チーム・日付で有効な時間割を検索する。
     * ACTIVE かつ effective_from <= date かつ (effective_until IS NULL OR effective_until >= date)。
     */
    @Query("SELECT t FROM TimetableEntity t WHERE t.teamId = :teamId"
            + " AND t.status = 'ACTIVE'"
            + " AND t.effectiveFrom <= :date"
            + " AND (t.effectiveUntil IS NULL OR t.effectiveUntil >= :date)")
    List<TimetableEntity> findEffective(@Param("teamId") Long teamId, @Param("date") LocalDate date);
}
