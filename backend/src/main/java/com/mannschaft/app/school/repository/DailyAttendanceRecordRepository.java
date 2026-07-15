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

    /**
     * 生徒の日次出欠をチーム指定なしで取得する（認可根治 束4: 生徒→所属チーム解決用）。
     *
     * <p>タイムライン閲覧（{@code AttendanceLocationService#getTimeline}）の認可判定で、
     * 呼び出し元が studentUserId しか持たない場合に「その生徒が当日在籍するチーム」を
     * 逆引きするために使用する。同一生徒・同一日で複数チームの記録が存在する運用は
     * 想定しないが、万一存在しても認可判定用の代表値として先頭1件で足りるため
     * {@code findFirst} で取得する。</p>
     */
    Optional<DailyAttendanceRecordEntity> findFirstByStudentUserIdAndAttendanceDate(
            Long studentUserId, LocalDate attendanceDate);

    /** 生徒の期間内日次出欠履歴を取得する。 */
    List<DailyAttendanceRecordEntity> findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            Long studentUserId, LocalDate from, LocalDate to);

    /** 特定ステータスの生徒を取得する（点呼後の統計用）。 */
    List<DailyAttendanceRecordEntity> findByTeamIdAndAttendanceDateAndStatus(
            Long teamId, LocalDate attendanceDate, AttendanceStatus status);

    /** クラスの期間内日次出欠を一括取得（月次集計・CSV エクスポート用）。 */
    List<DailyAttendanceRecordEntity> findByTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            Long teamId, LocalDate from, LocalDate to);

    /** 生徒の期間内日次出欠を取得（学期別統計用）。 */
    List<DailyAttendanceRecordEntity> findByStudentUserIdAndTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
            Long studentUserId, Long teamId, LocalDate from, LocalDate to);
}
