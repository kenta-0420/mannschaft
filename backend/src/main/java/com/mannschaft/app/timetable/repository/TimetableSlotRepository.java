package com.mannschaft.app.timetable.repository;

import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 時間割スロットリポジトリ。
 */
public interface TimetableSlotRepository extends JpaRepository<TimetableSlotEntity, Long> {

    List<TimetableSlotEntity> findByTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(Long timetableId);

    List<TimetableSlotEntity> findByTimetableIdAndDayOfWeek(Long timetableId, String dayOfWeek);

    void deleteByTimetableId(Long timetableId);

    /**
     * 指定チームの全時間割から教科名を重複なしで取得する（サジェスト用）。
     */
    @Query("SELECT DISTINCT s.subjectName FROM TimetableSlotEntity s"
            + " WHERE s.timetableId IN (SELECT t.id FROM TimetableEntity t WHERE t.teamId = :teamId)")
    List<String> findDistinctSubjectNamesByTeamId(@Param("teamId") Long teamId);
}
