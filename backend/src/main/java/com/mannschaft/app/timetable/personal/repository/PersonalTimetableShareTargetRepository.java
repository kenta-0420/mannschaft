package com.mannschaft.app.timetable.personal.repository;

import com.mannschaft.app.timetable.personal.entity.PersonalTimetableShareTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F03.15 個人時間割共有先リポジトリ（Phase 5 で本格使用）。
 */
public interface PersonalTimetableShareTargetRepository
        extends JpaRepository<PersonalTimetableShareTargetEntity, Long> {

    List<PersonalTimetableShareTargetEntity> findByPersonalTimetableId(Long personalTimetableId);
}
