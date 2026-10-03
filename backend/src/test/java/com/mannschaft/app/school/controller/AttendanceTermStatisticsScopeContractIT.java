package com.mannschaft.app.school.controller;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.entity.PeriodAttendanceRecordEntity;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.PeriodAttendanceRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 期間別出欠集計（{@code AttendanceStatisticsController#getTermStatistics}）の自己スコープ契約テスト
 * （実 MySQL + 実 Spring MVC）。
 *
 * <p>集計の対象は認証主体本人の出欠行だけである。{@code teamId} は集計対象のクラスを選ぶ条件にすぎず、
 * 同じクラスに在籍する他の生徒の出欠や、別クラスの他人の出欠が集計に入らないこと、
 * 同じ URL で認証主体だけを差し替えるとそれぞれ自分の集計だけが返ることを固定する。</p>
 *
 * <p>金型: {@code ChartScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 手動 SecurityContext）。
 * 日付は実行時の {@link LocalDate#now()} から採り、固定日付を書かない。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("期間別出欠集計 自己スコープ契約テスト（AttendanceStatisticsController#getTermStatistics）")
class AttendanceTermStatisticsScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final Long ME = 18_360_001L;
    private static final Long OTHER = 18_360_002L;
    private static final Long NO_RECORD_USER = 18_360_003L;
    private static final Long TEACHER = 18_360_009L;
    private static final Long TEAM_A = 18_361_001L;
    private static final Long TEAM_B = 18_361_002L;
    private static final Long TEAM_UNRELATED = 18_361_999L;

    private static final String TERM_URL = "/api/v1/me/attendance/statistics/term";

    @Autowired private MockMvc mockMvc;
    @Autowired private DailyAttendanceRecordRepository dailyRepository;
    @Autowired private PeriodAttendanceRecordRepository periodRepository;

    private LocalDate today;
    private String from;
    private String to;

    @BeforeEach
    void setUp() {
        today = LocalDate.now();
        from = today.minusDays(10).toString();
        to = today.toString();

        // 自分: A 組に出席 1 日・欠席 1 日、B 組に出席 1 日。
        persistDaily(ME, TEAM_A, today.minusDays(1), AttendanceStatus.ATTENDING);
        persistDaily(ME, TEAM_A, today.minusDays(2), AttendanceStatus.ABSENT);
        persistDaily(ME, TEAM_B, today.minusDays(3), AttendanceStatus.ATTENDING);
        // 他人: A 組に欠席 3 日、B 組に欠席 1 日。
        persistDaily(OTHER, TEAM_A, today.minusDays(1), AttendanceStatus.ABSENT);
        persistDaily(OTHER, TEAM_A, today.minusDays(2), AttendanceStatus.ABSENT);
        persistDaily(OTHER, TEAM_A, today.minusDays(3), AttendanceStatus.ABSENT);
        persistDaily(OTHER, TEAM_B, today.minusDays(4), AttendanceStatus.ABSENT);

        // 本人に記録のないクラスにも、他人の出欠が実在する。
        persistDaily(OTHER, TEAM_UNRELATED, today.minusDays(1), AttendanceStatus.ABSENT);
        persistPeriod(OTHER, TEAM_UNRELATED, today.minusDays(1), 1, "数学", AttendanceStatus.ABSENT);

        persistPeriod(ME, TEAM_A, today.minusDays(1), 1, "国語", AttendanceStatus.ATTENDING);
        persistPeriod(OTHER, TEAM_A, today.minusDays(1), 1, "国語", AttendanceStatus.ABSENT);
        persistPeriod(OTHER, TEAM_A, today.minusDays(1), 2, "数学", AttendanceStatus.ABSENT);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.getContext().setAuthentication(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("自分の出欠だけが集計され、同じクラスの他人の出欠は入らない")
    void term_aggregatesOnlyOwnRecords() throws Exception {
        setAuth(ME);
        mockMvc.perform(get(TERM_URL).param("teamId", TEAM_A.toString()).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentUserId").value(ME))
                .andExpect(jsonPath("$.data.totalSchoolDays").value(2))
                .andExpect(jsonPath("$.data.presentDays").value(1))
                .andExpect(jsonPath("$.data.absentDays").value(1))
                .andExpect(jsonPath("$.data.subjectBreakdown.length()").value(1))
                .andExpect(jsonPath("$.data.subjectBreakdown[0].subjectName").value("国語"))
                .andExpect(jsonPath("$.data.subjectBreakdown[0].totalPeriods").value(1))
                .andExpect(jsonPath("$.data.subjectBreakdown[0].presentPeriods").value(1));
    }

    @Test
    @DisplayName("teamId を別クラスに変えても、そのクラスの他人の出欠は集計に入らない")
    void term_withOtherTeamId_stillExcludesOthers() throws Exception {
        setAuth(ME);
        mockMvc.perform(get(TERM_URL).param("teamId", TEAM_B.toString()).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentUserId").value(ME))
                .andExpect(jsonPath("$.data.totalSchoolDays").value(1))
                .andExpect(jsonPath("$.data.presentDays").value(1))
                .andExpect(jsonPath("$.data.absentDays").value(0));
    }

    @Test
    @DisplayName("自分に出欠記録のないクラスの ID を指定すると、他人の出欠ではなく空の集計が返る")
    void term_withUnrelatedTeamId_returnsEmptyTotals() throws Exception {
        // 対象データが実在することの確認: 記録の持ち主には集計が返る。
        setAuth(OTHER);
        mockMvc.perform(get(TERM_URL).param("teamId", TEAM_UNRELATED.toString()).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentUserId").value(OTHER))
                .andExpect(jsonPath("$.data.totalSchoolDays").value(1))
                .andExpect(jsonPath("$.data.absentDays").value(1))
                .andExpect(jsonPath("$.data.subjectBreakdown[?(@.subjectName=='数学')]").isNotEmpty());

        // 同じ URL で認証主体を本人に差し替えると、他人の出欠は集計に入らない。
        setAuth(ME);
        mockMvc.perform(get(TERM_URL).param("teamId", TEAM_UNRELATED.toString()).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentUserId").value(ME))
                .andExpect(jsonPath("$.data.totalSchoolDays").value(0))
                .andExpect(jsonPath("$.data.presentDays").value(0))
                .andExpect(jsonPath("$.data.absentDays").value(0))
                .andExpect(jsonPath("$.data.subjectBreakdown[?(@.subjectName=='数学')]").isEmpty());
    }

    @Test
    @DisplayName("同じ URL で認証主体だけ差し替えると、それぞれ自分の集計だけが返る")
    void term_sameRequestUnderDifferentPrincipal_returnsEachOwnTotals() throws Exception {
        setAuth(OTHER);
        mockMvc.perform(get(TERM_URL).param("teamId", TEAM_A.toString()).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentUserId").value(OTHER))
                .andExpect(jsonPath("$.data.totalSchoolDays").value(3))
                .andExpect(jsonPath("$.data.presentDays").value(0))
                .andExpect(jsonPath("$.data.absentDays").value(3))
                .andExpect(jsonPath("$.data.subjectBreakdown.length()").value(2));

        setAuth(ME);
        mockMvc.perform(get(TERM_URL).param("teamId", TEAM_A.toString()).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentUserId").value(ME))
                .andExpect(jsonPath("$.data.totalSchoolDays").value(2))
                .andExpect(jsonPath("$.data.absentDays").value(1));
    }

    @Test
    @DisplayName("出欠記録を持たない利用者の集計は空")
    void term_userWithoutRecords_returnsEmptyTotals() throws Exception {
        setAuth(NO_RECORD_USER);
        mockMvc.perform(get(TERM_URL).param("teamId", TEAM_A.toString()).param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.studentUserId").value(NO_RECORD_USER))
                .andExpect(jsonPath("$.data.totalSchoolDays").value(0))
                .andExpect(jsonPath("$.data.presentDays").value(0))
                .andExpect(jsonPath("$.data.absentDays").value(0))
                .andExpect(jsonPath("$.data.subjectBreakdown.length()").value(0));
    }

    private void persistDaily(Long studentUserId, Long teamId, LocalDate date, AttendanceStatus status) {
        dailyRepository.save(DailyAttendanceRecordEntity.builder()
                .teamId(teamId)
                .studentUserId(studentUserId)
                .attendanceDate(date)
                .status(status)
                .recordedBy(TEACHER)
                .build());
    }

    private void persistPeriod(Long studentUserId, Long teamId, LocalDate date, int periodNumber,
                               String subjectName, AttendanceStatus status) {
        periodRepository.save(PeriodAttendanceRecordEntity.builder()
                .teamId(teamId)
                .studentUserId(studentUserId)
                .attendanceDate(date)
                .periodNumber(periodNumber)
                .subjectName(subjectName)
                .status(status)
                .recordedBy(TEACHER)
                .build());
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
