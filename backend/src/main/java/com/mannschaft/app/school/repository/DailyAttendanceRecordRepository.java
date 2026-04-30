package com.mannschaft.app.school.repository;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 日次出欠リポジトリ。 */
public interface DailyAttendanceRecordRepository extends JpaRepository<DailyAttendanceRecordEntity, Long> {

    /** クラスの特定日の日次出欠一覧を取得する。 */
    List<DailyAttendanceRecordEntity> findByTeamIdAndAttendanceDate(Long teamId, LocalDate attendanceDate);

    /** 生徒の日次出欠を取得する。 */
    Optional<DailyAttendanceRecordEntity> findByTeamIdAndStudentUserIdAndAttendanceDate(
            Long teamId, Long studentUserId, LocalDate attendanceDate);

    /** 生徒の期間内日次出欠履歴を取得する。 */
    List<DailyAttendanceRecordEntity> findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            Long studentUserId, LocalDate from, LocalDate to);

    /** 特定ステータスの生徒を取得する（点呼後の統計用）。 */
    List<DailyAttendanceRecordEntity> findByTeamIdAndAttendanceDateAndStatus(
            Long teamId, LocalDate attendanceDate, AttendanceStatus status);
}
