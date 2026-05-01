package com.mannschaft.app.school.service;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.dto.MonthlyStatisticsResponse;
import com.mannschaft.app.school.dto.StudentTermStatisticsResponse;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * F03.13: {@link AttendanceStatisticsService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AttendanceStatisticsService 単体テスト")
class AttendanceStatisticsServiceTest {

    private static final Long TEAM_ID = 100L;
    private static final Long STUDENT_ID_1 = 1L;
    private static final Long STUDENT_ID_2 = 2L;
    private static final LocalDate DATE_2026_05_01 = LocalDate.of(2026, 5, 1);
    private static final LocalDate DATE_2026_05_02 = LocalDate.of(2026, 5, 2);

    @Mock
    private DailyAttendanceRecordRepository dailyRepo;

    @Mock
    private PeriodAttendanceRecordRepository periodRepo;

    @InjectMocks
    private AttendanceStatisticsService service;

    // ────────────────────────────────────────────────
    // getMonthlyStatistics
    // ────────────────────────────────────────────────

    @Nested
    @DisplayName("getMonthlyStatistics")
    class GetMonthlyStatistics {

        @Test
        @DisplayName("正常系: 出席・欠席が混在するとき → 出席率が正しく計算される")
        void 正常系_出席率計算() {
            // 生徒1: 2日とも出席、生徒2: 1日出席 1日欠席
            DailyAttendanceRecordEntity r1 = buildDailyRecord(STUDENT_ID_1, DATE_2026_05_01, AttendanceStatus.ATTENDING, null, null);
            DailyAttendanceRecordEntity r2 = buildDailyRecord(STUDENT_ID_1, DATE_2026_05_02, AttendanceStatus.ATTENDING, null, null);
            DailyAttendanceRecordEntity r3 = buildDailyRecord(STUDENT_ID_2, DATE_2026_05_01, AttendanceStatus.ATTENDING, null, null);
            DailyAttendanceRecordEntity r4 = buildDailyRecord(STUDENT_ID_2, DATE_2026_05_02, AttendanceStatus.ABSENT, null, null);

            given(dailyRepo.findByTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                    eq(TEAM_ID), any(LocalDate.class), any(LocalDate.class)))
                    .willReturn(List.of(r1, r2, r3, r4));
            given(periodRepo.findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc(
                    any(Long.class), any(LocalDate.class), any(LocalDate.class)))
                    .willReturn(List.of());

            MonthlyStatisticsResponse result = service.getMonthlyStatistics(TEAM_ID, 2026, 5);

            assertThat(result.getYear()).isEqualTo(2026);
            assertThat(result.getMonth()).isEqualTo(5);
            assertThat(result.getTotalSchoolDays()).isEqualTo(2);
            assertThat(result.getTotalStudents()).isEqualTo(2);
            assertThat(result.getPresentCount()).isEqualTo(3);
            assertThat(result.getAbsentCount()).isEqualTo(1);
            // 出席率 = 3 / (2 * 2) * 100 = 75.00
            assertThat(result.getAttendanceRate()).isEqualByComparingTo(new BigDecimal("75.00"));
            assertThat(result.getStudentBreakdown()).hasSize(2);
        }

        @Test
        @DisplayName("レコードなし: 空リストのとき → ゼロ値のレスポンスを返す")
        void レコードなし_ゼロ値() {
            given(dailyRepo.findByTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                    eq(TEAM_ID), any(LocalDate.class), any(LocalDate.class)))
                    .willReturn(List.of());

            MonthlyStatisticsResponse result = service.getMonthlyStatistics(TEAM_ID, 2026, 5);

            assertThat(result.getTotalSchoolDays()).isEqualTo(0);
            assertThat(result.getTotalStudents()).isEqualTo(0);
            assertThat(result.getPresentCount()).isEqualTo(0);
            assertThat(result.getAttendanceRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.getStudentBreakdown()).isEmpty();
        }

        @Test
        @DisplayName("生徒別内訳: 複数生徒のデータが studentUserId 昇順でソートされる")
        void 生徒別内訳_ソート順() {
            DailyAttendanceRecordEntity r1 = buildDailyRecord(STUDENT_ID_2, DATE_2026_05_01, AttendanceStatus.ATTENDING, null, null);
            DailyAttendanceRecordEntity r2 = buildDailyRecord(STUDENT_ID_1, DATE_2026_05_01, AttendanceStatus.ABSENT, null, null);

            given(dailyRepo.findByTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                    eq(TEAM_ID), any(LocalDate.class), any(LocalDate.class)))
                    .willReturn(List.of(r1, r2));
            given(periodRepo.findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc(
                    any(Long.class), any(LocalDate.class), any(LocalDate.class)))
                    .willReturn(List.of());

            MonthlyStatisticsResponse result = service.getMonthlyStatistics(TEAM_ID, 2026, 5);

            assertThat(result.getStudentBreakdown()).extracting("studentUserId")
                    .containsExactly(STUDENT_ID_1, STUDENT_ID_2);
        }
    }

    // ────────────────────────────────────────────────
    // getStudentTermStatistics
    // ────────────────────────────────────────────────

    @Nested
    @DisplayName("getStudentTermStatistics")
    class GetStudentTermStatistics {

        private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
        private static final LocalDate TO   = LocalDate.of(2026, 4, 30);

        @Test
        @DisplayName("正常系: 出席・欠席・PARTIAL が混在するとき → 出席率が計算される")
        void 正常系_出席率計算() {
            DailyAttendanceRecordEntity r1 = buildDailyRecord(STUDENT_ID_1, FROM, AttendanceStatus.ATTENDING, null, null);
            DailyAttendanceRecordEntity r2 = buildDailyRecord(STUDENT_ID_1, FROM.plusDays(1), AttendanceStatus.ABSENT, null, null);
            DailyAttendanceRecordEntity r3 = buildDailyRecord(STUDENT_ID_1, FROM.plusDays(2), AttendanceStatus.PARTIAL,
                    LocalTime.of(9, 30), null);

            given(dailyRepo.findByStudentUserIdAndTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                    eq(STUDENT_ID_1), eq(TEAM_ID), eq(FROM), eq(TO)))
                    .willReturn(List.of(r1, r2, r3));
            given(periodRepo.findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc(
                    eq(STUDENT_ID_1), eq(FROM), eq(TO)))
                    .willReturn(List.of());

            StudentTermStatisticsResponse result =
                    service.getStudentTermStatistics(STUDENT_ID_1, TEAM_ID, FROM, TO);

            assertThat(result.getStudentUserId()).isEqualTo(STUDENT_ID_1);
            assertThat(result.getTotalSchoolDays()).isEqualTo(3);
            assertThat(result.getPresentDays()).isEqualTo(2); // ATTENDING + PARTIAL
            assertThat(result.getAbsentDays()).isEqualTo(1);
            // 出席率 = 2/3 * 100 ≒ 66.67
            assertThat(result.getAttendanceRate()).isEqualByComparingTo(new BigDecimal("66.67"));
        }

        @Test
        @DisplayName("遅刻・早退カウント: PARTIAL + arrivalTime/leaveTime で正しくカウントされる")
        void 遅刻早退カウント() {
            DailyAttendanceRecordEntity late = buildDailyRecord(STUDENT_ID_1, FROM,
                    AttendanceStatus.PARTIAL, LocalTime.of(10, 0), null);
            DailyAttendanceRecordEntity earlyLeave = buildDailyRecord(STUDENT_ID_1, FROM.plusDays(1),
                    AttendanceStatus.PARTIAL, null, LocalTime.of(13, 0));
            DailyAttendanceRecordEntity attending = buildDailyRecord(STUDENT_ID_1, FROM.plusDays(2),
                    AttendanceStatus.ATTENDING, null, null);

            given(dailyRepo.findByStudentUserIdAndTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                    eq(STUDENT_ID_1), eq(TEAM_ID), eq(FROM), eq(TO)))
                    .willReturn(List.of(late, earlyLeave, attending));
            given(periodRepo.findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc(
                    eq(STUDENT_ID_1), eq(FROM), eq(TO)))
                    .willReturn(List.of());

            StudentTermStatisticsResponse result =
                    service.getStudentTermStatistics(STUDENT_ID_1, TEAM_ID, FROM, TO);

            assertThat(result.getLateCount()).isEqualTo(1);
            assertThat(result.getEarlyLeaveCount()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────
    // exportAttendanceCsv
    // ────────────────────────────────────────────────

    @Nested
    @DisplayName("exportAttendanceCsv")
    class ExportAttendanceCsv {

        @Test
        @DisplayName("正常系: レコードがあるとき → ヘッダー行 + データ行の CSV バイト配列が返される")
        void 正常系_CSVバイト配列() {
            DailyAttendanceRecordEntity r = buildDailyRecord(STUDENT_ID_1, DATE_2026_05_01,
                    AttendanceStatus.ABSENT, null, null);

            given(dailyRepo.findByTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                    eq(TEAM_ID), eq(DATE_2026_05_01), eq(DATE_2026_05_02)))
                    .willReturn(List.of(r));

            byte[] csv = service.exportAttendanceCsv(TEAM_ID, DATE_2026_05_01, DATE_2026_05_02);

            String content = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(content).startsWith("studentUserId,attendanceDate,status");
            assertThat(content).contains(STUDENT_ID_1.toString());
            assertThat(content).contains("ABSENT");
        }
    }

    // ────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────

    private DailyAttendanceRecordEntity buildDailyRecord(
            Long studentUserId, LocalDate date, AttendanceStatus status,
            LocalTime arrivalTime, LocalTime leaveTime) {

        DailyAttendanceRecordEntity entity = DailyAttendanceRecordEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(studentUserId)
                .attendanceDate(date)
                .status(status)
                .arrivalTime(arrivalTime)
                .leaveTime(leaveTime)
                .recordedBy(99L)
                .build();
        ReflectionTestUtils.setField(entity, "id", studentUserId * 1000 + date.getDayOfMonth());
        return entity;
    }

    private PeriodAttendanceRecordEntity buildPeriodRecord(
            Long studentUserId, LocalDate date, int period,
            String subjectName, AttendanceStatus status) {

        PeriodAttendanceRecordEntity entity = PeriodAttendanceRecordEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(studentUserId)
                .attendanceDate(date)
                .periodNumber(period)
                .subjectName(subjectName)
                .status(status)
                .recordedBy(99L)
                .build();
        ReflectionTestUtils.setField(entity, "id", studentUserId * 100 + period);
        return entity;
    }
}
