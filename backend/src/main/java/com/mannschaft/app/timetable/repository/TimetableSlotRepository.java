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

    /** 時間割 ID 群のスロットを一括取得する（ダッシュボード N+1 解消用。時間割 ID・主キー順）。 */
    List<TimetableSlotEntity> findByTimetableIdInOrderByTimetableIdAscIdAsc(java.util.Collection<Long> timetableIds);

    /** 時間割 ID 群の指定曜日スロットを一括取得する（ダッシュボード N+1 解消用。時間割 ID・主キー順）。 */
    List<TimetableSlotEntity> findByTimetableIdInAndDayOfWeekOrderByTimetableIdAscIdAsc(
            java.util.Collection<Long> timetableIds, String dayOfWeek);

    void deleteByTimetableId(Long timetableId);

    /**
     * 指定チームの全時間割から教科名を重複なしで取得する（サジェスト用）。
     */
    @Query("SELECT DISTINCT s.subjectName FROM TimetableSlotEntity s"
            + " WHERE s.timetableId IN (SELECT t.id FROM TimetableEntity t WHERE t.teamId = :teamId)")
    List<String> findDistinctSubjectNamesByTeamId(@Param("teamId") Long teamId);
}
