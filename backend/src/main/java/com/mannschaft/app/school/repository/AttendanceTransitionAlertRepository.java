package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** 「前にいたのに今いない」検知ログリポジトリ。 */
public interface AttendanceTransitionAlertRepository extends JpaRepository<AttendanceTransitionAlertEntity, Long> {

    /** クラスの特定日のアラート一覧（未解決含む全件）を取得する。 */
    List<AttendanceTransitionAlertEntity> findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(
            Long teamId, LocalDate attendanceDate);

    /** クラスの特定日の未解決アラート一覧を取得する。 */
    List<AttendanceTransitionAlertEntity> findByTeamIdAndAttendanceDateAndResolvedAtIsNullOrderByCreatedAtDesc(
            Long teamId, LocalDate attendanceDate);

    /** 生徒の特定日に未解決アラートが存在するか確認する（重複防止用）。 */
    boolean existsByTeamIdAndStudentUserIdAndAttendanceDateAndResolvedAtIsNull(
            Long teamId, Long studentUserId, LocalDate attendanceDate);
}
