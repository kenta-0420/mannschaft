package com.mannschaft.app.timetable.personal.repository;

import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F03.15 個人時間割コマリポジトリ。
 */
public interface PersonalTimetableSlotRepository extends JpaRepository<PersonalTimetableSlotEntity, Long> {

    /**
     * 個人時間割 ID でコマを全件取得（曜日→時限順）。
     */
    List<PersonalTimetableSlotEntity> findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(
            Long personalTimetableId);

    /**
     * 個人時間割 ID でコマを一括削除。
     */
    void deleteByPersonalTimetableId(Long personalTimetableId);
}
