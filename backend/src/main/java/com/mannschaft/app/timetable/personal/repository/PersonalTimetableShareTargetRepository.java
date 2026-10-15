package com.mannschaft.app.timetable.personal.repository;

import com.mannschaft.app.timetable.personal.entity.PersonalTimetableShareTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * F03.15 個人時間割共有先リポジトリ（Phase 5 で本格使用）。
 */
public interface PersonalTimetableShareTargetRepository
        extends JpaRepository<PersonalTimetableShareTargetEntity, Long> {

    List<PersonalTimetableShareTargetEntity> findByPersonalTimetableId(Long personalTimetableId);

    /** 件数カウント（最大3件チェック用）。 */
    long countByPersonalTimetableId(Long personalTimetableId);

    /** 重複チェック用。 */
    boolean existsByPersonalTimetableIdAndTeamId(Long personalTimetableId, Long teamId);

    /** 個人時間割 + チーム ID で1件取得（家族閲覧 API の権限判定用）。 */
    Optional<PersonalTimetableShareTargetEntity> findByPersonalTimetableIdAndTeamId(
            Long personalTimetableId, Long teamId);

    /** 解除用に削除する。 */
    void deleteByPersonalTimetableIdAndTeamId(Long personalTimetableId, Long teamId);

    /** 家族チーム ID から共有された個人時間割 ID 一覧（家族閲覧 API 一覧取得用）。 */
    @org.springframework.data.jpa.repository.Query(
            "SELECT s.personalTimetableId FROM PersonalTimetableShareTargetEntity s "
            + "WHERE s.teamId = :teamId")
    List<Long> findPersonalTimetableIdsByTeamId(
            @org.springframework.data.repository.query.Param("teamId") Long teamId);
}
