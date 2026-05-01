package com.mannschaft.app.school.service;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.AttendanceStatisticsSummary;
import com.mannschaft.app.school.dto.MonthlyStatisticsResponse;
import com.mannschaft.app.school.dto.StudentTermStatisticsResponse;
import com.mannschaft.app.school.dto.SubjectAttendanceBreakdown;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** F03.13 学校出欠: 出欠統計・CSV エクスポートサービス。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AttendanceStatisticsService {

    private final DailyAttendanceRecordRepository dailyRepo;
    private final PeriodAttendanceRecordRepository periodRepo;

    /**
     * 担任向け月次出欠集計を取得する。
     *
     * @param teamId クラスチームID
     * @param year   対象年
     * @param month  対象月（1〜12）
     * @return 月次集計レスポンス
     */
    public MonthlyStatisticsResponse getMonthlyStatistics(Long teamId, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());

        List<DailyAttendanceRecordEntity> records =
                dailyRepo.findByTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(teamId, from, to);

        if (records.isEmpty()) {
            return MonthlyStatisticsResponse.builder()
                    .year(year).month(month).teamId(teamId)
                    .totalSchoolDays(0).totalStudents(0)
                    .presentCount(0).absentCount(0).undecidedCount(0)
                    .attendanceRate(BigDecimal.ZERO)
                    .studentBreakdown(List.of())
                    .build();
        }

        int totalSchoolDays = (int) records.stream()
                .map(DailyAttendanceRecordEntity::getAttendanceDate)
                .distinct().count();

        int presentCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ATTENDING
                          || r.getStatus() == AttendanceStatus.PARTIAL)
                .count();
        int absentCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ABSENT).count();
        int undecidedCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.UNDECIDED).count();

        Map<Long, List<DailyAttendanceRecordEntity>> byStudent =
                records.stream().collect(Collectors.groupingBy(DailyAttendanceRecordEntity::getStudentUserId));

        List<AttendanceStatisticsSummary> breakdown = byStudent.entrySet().stream()
                .map(e -> buildStudentSummary(e.getKey(), e.getValue(), totalSchoolDays))
                .sorted(Comparator.comparing(AttendanceStatisticsSummary::getStudentUserId))
                .toList();

        int totalStudents = byStudent.size();
        BigDecimal rate = (totalStudents == 0 || totalSchoolDays == 0) ? BigDecimal.ZERO
                : BigDecimal.valueOf(presentCount * 100.0 / ((long) totalStudents * totalSchoolDays))
                        .setScale(2, RoundingMode.HALF_UP);

        return MonthlyStatisticsResponse.builder()
                .year(year).month(month).teamId(teamId)
                .totalSchoolDays(totalSchoolDays).totalStudents(totalStudents)
                .presentCount(presentCount).absentCount(absentCount).undecidedCount(undecidedCount)
                .attendanceRate(rate)
                .studentBreakdown(breakdown)
                .build();
    }

    /**
     * 生徒・保護者向け期間別出欠集計を取得する。
     *
     * @param studentUserId 生徒のユーザーID
     * @param teamId        クラスチームID
     * @param from          集計開始日
     * @param to            集計終了日
     * @return 期間別集計レスポンス
     */
    public StudentTermStatisticsResponse getStudentTermStatistics(
            Long studentUserId, Long teamId, LocalDate from, LocalDate to) {

        List<DailyAttendanceRecordEntity> dailyRecords =
                dailyRepo.findByStudentUserIdAndTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        studentUserId, teamId, from, to);

        int totalSchoolDays = (int) dailyRecords.stream()
                .map(DailyAttendanceRecordEntity::getAttendanceDate).distinct().count();
        int presentDays = (int) dailyRecords.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ATTENDING
                          || r.getStatus() == AttendanceStatus.PARTIAL).count();
        int absentDays = (int) dailyRecords.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ABSENT).count();
        int lateCount = (int) dailyRecords.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.PARTIAL && r.getArrivalTime() != null).count();
        int earlyLeaveCount = (int) dailyRecords.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.PARTIAL && r.getLeaveTime() != null).count();

        BigDecimal rate = totalSchoolDays == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(presentDays * 100.0 / totalSchoolDays)
                        .setScale(2, RoundingMode.HALF_UP);

        List<SubjectAttendanceBreakdown> subjectBreakdown = buildSubjectBreakdown(studentUserId, from, to);

        return StudentTermStatisticsResponse.builder()
                .studentUserId(studentUserId)
                .from(from).to(to)
                .totalSchoolDays(totalSchoolDays)
                .presentDays(presentDays).absentDays(absentDays)
                .lateCount(lateCount).earlyLeaveCount(earlyLeaveCount)
                .attendanceRate(rate)
                .subjectBreakdown(subjectBreakdown)
                .build();
    }

    /**
     * 担任向け出欠 CSV データを生成する。
     *
     * @param teamId クラスチームID
     * @param from   開始日
     * @param to     終了日
     * @return UTF-8 エンコードされた CSV バイト配列
     */
    public byte[] exportAttendanceCsv(Long teamId, LocalDate from, LocalDate to) {
        List<DailyAttendanceRecordEntity> records =
                dailyRepo.findByTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(teamId, from, to);

        StringBuilder sb = new StringBuilder();
        sb.append("studentUserId,attendanceDate,status,absenceReason,arrivalTime,leaveTime,comment\n");
        for (DailyAttendanceRecordEntity r : records) {
            sb.append(r.getStudentUserId()).append(",")
              .append(r.getAttendanceDate()).append(",")
              .append(r.getStatus()).append(",")
              .append(r.getAbsenceReason() != null ? r.getAbsenceReason() : "").append(",")
              .append(r.getArrivalTime() != null ? r.getArrivalTime() : "").append(",")
              .append(r.getLeaveTime() != null ? r.getLeaveTime() : "").append(",")
              .append(r.getComment() != null ? r.getComment().replace(",", "、") : "")
              .append("\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ────────────────────────────────────────────────
    // private helpers
    // ────────────────────────────────────────────────

    private AttendanceStatisticsSummary buildStudentSummary(
            Long studentUserId,
            List<DailyAttendanceRecordEntity> records,
            int totalSchoolDays) {

        int presentDays = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ATTENDING
                          || r.getStatus() == AttendanceStatus.PARTIAL).count();
        int absentDays = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.ABSENT).count();
        int lateCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.PARTIAL && r.getArrivalTime() != null).count();
        int earlyLeaveCount = (int) records.stream()
                .filter(r -> r.getStatus() == AttendanceStatus.PARTIAL && r.getLeaveTime() != null).count();
        BigDecimal rate = totalSchoolDays == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(presentDays * 100.0 / totalSchoolDays)
                        .setScale(2, RoundingMode.HALF_UP);

        return AttendanceStatisticsSummary.builder()
                .studentUserId(studentUserId)
                .presentDays(presentDays).absentDays(absentDays)
                .lateCount(lateCount).earlyLeaveCount(earlyLeaveCount)
                .attendanceRate(rate)
                .build();
    }

    private List<SubjectAttendanceBreakdown> buildSubjectBreakdown(
            Long studentUserId, LocalDate from, LocalDate to) {

        List<PeriodAttendanceRecordEntity> periodRecords =
                periodRepo.findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc(
                        studentUserId, from, to);

        if (periodRecords.isEmpty()) {
            return List.of();
        }

        Map<String, List<PeriodAttendanceRecordEntity>> bySubject = periodRecords.stream()
                .filter(r -> r.getSubjectName() != null)
                .collect(Collectors.groupingBy(PeriodAttendanceRecordEntity::getSubjectName));

        return bySubject.entrySet().stream()
                .map(e -> {
                    int total = e.getValue().size();
                    int present = (int) e.getValue().stream()
                            .filter(r -> r.getStatus() == AttendanceStatus.ATTENDING
                                      || r.getStatus() == AttendanceStatus.PARTIAL).count();
                    BigDecimal rate = total == 0 ? BigDecimal.ZERO
                            : BigDecimal.valueOf(present * 100.0 / total)
                                    .setScale(2, RoundingMode.HALF_UP);
                    return SubjectAttendanceBreakdown.builder()
                            .subjectName(e.getKey())
                            .totalPeriods(total)
                            .presentPeriods(present)
                            .attendanceRate(rate)
                            .build();
                })
                .sorted(Comparator.comparing(SubjectAttendanceBreakdown::getSubjectName))
                .toList();
    }
}
