package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.AttendanceLocationChangeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** 登校場所変更履歴リポジトリ。 */
public interface AttendanceLocationChangeRepository extends JpaRepository<AttendanceLocationChangeEntity, Long> {

    /** 生徒の特定日の場所変更履歴を時系列順に取得する。 */
    List<AttendanceLocationChangeEntity> findByStudentUserIdAndAttendanceDateOrderByRecordedAtAsc(
            Long studentUserId, LocalDate date);

    /** クラスの特定日の全場所変更履歴を生徒ID順に取得する。 */
    List<AttendanceLocationChangeEntity> findByTeamIdAndAttendanceDateOrderByStudentUserIdAsc(
            Long teamId, LocalDate date);
}
