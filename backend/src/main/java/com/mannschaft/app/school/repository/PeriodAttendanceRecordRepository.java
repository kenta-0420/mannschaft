package com.mannschaft.app.school.repository;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 時限別出欠リポジトリ。 */
public interface PeriodAttendanceRecordRepository extends JpaRepository<PeriodAttendanceRecordEntity, Long> {

    /** クラスの特定日・時限の出欠一覧を取得する。 */
    List<PeriodAttendanceRecordEntity> findByTeamIdAndAttendanceDateAndPeriodNumber(
            Long teamId, LocalDate attendanceDate, Integer periodNumber);

    /** 生徒の特定日の全時限出欠を取得する（タイムライン表示用）。 */
    List<PeriodAttendanceRecordEntity> findByStudentUserIdAndAttendanceDateOrderByPeriodNumberAsc(
            Long studentUserId, LocalDate attendanceDate);

    /** 生徒の直前時限の出欠を取得する（移動検知用）。 */
    Optional<PeriodAttendanceRecordEntity> findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumber(
            Long teamId, Long studentUserId, LocalDate attendanceDate, Integer periodNumber);

    /** 生徒の特定日で指定時限より前の出欠を降順で取得する（移動検知：直前時限の特定用）。 */
    List<PeriodAttendanceRecordEntity> findByTeamIdAndStudentUserIdAndAttendanceDateAndPeriodNumberLessThanOrderByPeriodNumberDesc(
            Long teamId, Long studentUserId, LocalDate attendanceDate, Integer periodNumber);

    /** クラスの特定日・時限の特定ステータス人数カウント（統計用）。 */
    long countByTeamIdAndAttendanceDateAndPeriodNumberAndStatus(
            Long teamId, LocalDate attendanceDate, Integer periodNumber, AttendanceStatus status);

    /** クラスの期間内時限別出欠を一括取得（教科別統計用）。 */
    List<PeriodAttendanceRecordEntity> findByTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc(
            Long teamId, LocalDate from, LocalDate to);

    /** 生徒の期間内時限別出欠を取得（教科別出席率用）。 */
    List<PeriodAttendanceRecordEntity> findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc(
            Long studentUserId, LocalDate from, LocalDate to);
}
