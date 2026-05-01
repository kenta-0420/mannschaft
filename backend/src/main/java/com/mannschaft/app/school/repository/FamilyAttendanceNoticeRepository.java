package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** 保護者連絡リポジトリ。 */
public interface FamilyAttendanceNoticeRepository extends JpaRepository<FamilyAttendanceNoticeEntity, Long> {

    /** クラスの特定日の連絡一覧を取得する。 */
    List<FamilyAttendanceNoticeEntity> findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(
            Long teamId, LocalDate attendanceDate);

    /** 生徒の特定日の連絡を取得する（移動検知・自動適用用）。 */
    List<FamilyAttendanceNoticeEntity> findByStudentUserIdAndAttendanceDate(
            Long studentUserId, LocalDate attendanceDate);

    /** クラスの特定日の未確認連絡一覧を取得する。 */
    List<FamilyAttendanceNoticeEntity> findByTeamIdAndAttendanceDateAndAcknowledgedByIsNull(
            Long teamId, LocalDate attendanceDate);

    /** 保護者の送信履歴を期間指定で取得する。 */
    List<FamilyAttendanceNoticeEntity> findBySubmitterUserIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(
            Long submitterUserId, LocalDate from, LocalDate to);
}
