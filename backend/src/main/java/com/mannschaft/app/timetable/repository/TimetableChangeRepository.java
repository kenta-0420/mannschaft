package com.mannschaft.app.timetable.repository;

import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 時間割変更リポジトリ。
 */
public interface TimetableChangeRepository extends JpaRepository<TimetableChangeEntity, Long> {

    List<TimetableChangeEntity> findByTimetableIdAndTargetDateOrderByPeriodNumber(Long timetableId, LocalDate targetDate);

    /** 時間割 ID 群の当日の変更を一括取得する（ダッシュボード N+1 解消用）。 */
    List<TimetableChangeEntity> findByTimetableIdInAndTargetDateOrderByPeriodNumber(
            java.util.Collection<Long> timetableIds, LocalDate targetDate);

    List<TimetableChangeEntity> findByTimetableIdAndTargetDateBetweenOrderByTargetDateAscPeriodNumberAsc(
            Long timetableId, LocalDate from, LocalDate to);

    Optional<TimetableChangeEntity> findByTimetableIdAndTargetDateAndPeriodNumberIsNull(Long timetableId, LocalDate targetDate);

    Optional<TimetableChangeEntity> findByIdAndTimetableId(Long id, Long timetableId);
}
