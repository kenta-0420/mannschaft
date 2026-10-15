package com.mannschaft.app.timetable.personal.repository;

import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 個人時間割 ID + 曜日でコマを取得（時限順）。
     */
    List<PersonalTimetableSlotEntity> findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
            Long personalTimetableId, String dayOfWeek);

    /**
     * 個人時間割 ID でコマを一括削除。
     */
    @Modifying
    @Query("DELETE FROM PersonalTimetableSlotEntity s WHERE s.personalTimetableId = :pid")
    void deleteByPersonalTimetableId(@Param("pid") Long personalTimetableId);

    /**
     * 個人時間割 ID + 曜日でコマを一括削除。日次の差し替えで使用。
     */
    @Modifying
    @Query("DELETE FROM PersonalTimetableSlotEntity s "
            + "WHERE s.personalTimetableId = :pid AND s.dayOfWeek = :dow")
    void deleteByPersonalTimetableIdAndDayOfWeek(
            @Param("pid") Long personalTimetableId,
            @Param("dow") String dayOfWeek);

    /**
     * コマ数カウント（上限チェック用）。
     */
    long countByPersonalTimetableId(Long personalTimetableId);

    /**
     * Phase 4: linked_timetable_id を持つコマを全取得（休講自動カレンダー反映用）。
     */
    List<PersonalTimetableSlotEntity> findByLinkedTimetableId(Long linkedTimetableId);

    /**
     * Phase 4: linked_team_id を持つコマを全取得（脱退時のリンク自動解除用）。
     */
    List<PersonalTimetableSlotEntity> findByLinkedTeamId(Long linkedTeamId);

    /**
     * Phase 4: linked_team_id を持つ自分のコマを取得（ユーザーの脱退時クリーンアップ用）。
     */
    @Query("SELECT s FROM PersonalTimetableSlotEntity s "
            + "JOIN PersonalTimetableEntity pt ON pt.id = s.personalTimetableId "
            + "WHERE pt.userId = :userId AND s.linkedTeamId = :teamId AND pt.deletedAt IS NULL")
    List<PersonalTimetableSlotEntity> findByLinkedTeamIdAndOwnerUserId(
            @Param("userId") Long userId, @Param("teamId") Long teamId);
}
