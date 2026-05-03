package com.mannschaft.app.timetable.personal.repository;

import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * F03.15 個人時間割時限定義リポジトリ。
 */
public interface PersonalTimetablePeriodRepository extends JpaRepository<PersonalTimetablePeriodEntity, Long> {

    /**
     * 個人時間割 ID で時限を全件取得（period_number 昇順）。
     */
    List<PersonalTimetablePeriodEntity> findByPersonalTimetableIdOrderByPeriodNumberAsc(Long personalTimetableId);

    /**
     * 個人時間割 ID で時限を一括削除。duplicate / 上書きで使用。
     */
    @Modifying
    @Query("DELETE FROM PersonalTimetablePeriodEntity p WHERE p.personalTimetableId = :pid")
    void deleteByPersonalTimetableId(@Param("pid") Long personalTimetableId);
}
