package com.mannschaft.app.timetable.personal.repository;

import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F03.15 個人時間割ユーザー設定リポジトリ（Phase 3 で本格使用）。
 */
public interface PersonalTimetableSettingsRepository
        extends JpaRepository<PersonalTimetableSettingsEntity, Long> {
}
