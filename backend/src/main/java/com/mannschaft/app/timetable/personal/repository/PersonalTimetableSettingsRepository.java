package com.mannschaft.app.timetable.personal.repository;

import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F03.15 個人時間割ユーザー設定リポジトリ（Phase 3）。
 *
 * <p>PK は user_id。{@code findById(userId)} で取得し、未存在なら
 * Service 層がデフォルトを INSERT する（UPSERT 動作）。</p>
 */
public interface PersonalTimetableSettingsRepository
        extends JpaRepository<PersonalTimetableSettingsEntity, Long> {
}
