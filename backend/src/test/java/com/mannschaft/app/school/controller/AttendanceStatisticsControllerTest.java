package com.mannschaft.app.school.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.school.dto.AttendanceStatisticsSummary;
import com.mannschaft.app.school.dto.MonthlyStatisticsResponse;
import com.mannschaft.app.school.dto.StudentTermStatisticsResponse;
import com.mannschaft.app.school.service.AttendanceStatisticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.13: {@link AttendanceStatisticsController} の MockMvc 結合テスト。
 */
@WebMvcTest(AttendanceStatisticsController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AttendanceStatisticsController 結合テスト")
class AttendanceStatisticsControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttendanceStatisticsService statisticsService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDownSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/attendance/statistics/monthly
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/attendance/statistics/monthly")
    class GetMonthlyStatistics {

        @Test
        @DisplayName("正常系: 月次集計を返す → 200 + data")
        void 正常系_200() throws Exception {
            AttendanceStatisticsSummary summary = AttendanceStatisticsSummary.builder()
                    .studentUserId(10L)
                    .presentDays(20).absentDays(1)
                    .lateCount(0).earlyLeaveCount(0)
                    .attendanceRate(new BigDecimal("95.24"))
                    .build();

            MonthlyStatisticsResponse response = MonthlyStatisticsResponse.builder()
                    .year(2026).month(5).teamId(TEAM_ID)
                    .totalSchoolDays(21).totalStudents(1)
                    .presentCount(20).absentCount(1).undecidedCount(0)
                    .attendanceRate(new BigDecimal("95.24"))
                    .studentBreakdown(List.of(summary))
                    .build();

            given(statisticsService.getMonthlyStatistics(eq(TEAM_ID), eq(2026), eq(5)))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/statistics/monthly", TEAM_ID)
                            .param("year", "2026")
                            .param("month", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.year").value(2026))
                    .andExpect(jsonPath("$.data.month").value(5))
                    .andExpect(jsonPath("$.data.totalSchoolDays").value(21))
                    .andExpect(jsonPath("$.data.attendanceRate").value(95.24))
                    .andExpect(jsonPath("$.data.studentBreakdown").isArray());
        }
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/me/attendance/statistics/term
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/me/attendance/statistics/term")
    class GetTermStatistics {

        @Test
        @DisplayName("正常系: 期間別集計を返す → 200 + data")
        void 正常系_200() throws Exception {
            StudentTermStatisticsResponse response = StudentTermStatisticsResponse.builder()
                    .studentUserId(USER_ID)
                    .from(LocalDate.of(2026, 4, 1))
                    .to(LocalDate.of(2026, 4, 30))
                    .totalSchoolDays(20)
                    .presentDays(18).absentDays(2)
                    .lateCount(1).earlyLeaveCount(0)
                    .attendanceRate(new BigDecimal("90.00"))
                    .subjectBreakdown(List.of())
                    .build();

            given(statisticsService.getStudentTermStatistics(
                    eq(USER_ID), eq(TEAM_ID),
                    eq(LocalDate.of(2026, 4, 1)), eq(LocalDate.of(2026, 4, 30))))
                    .willReturn(response);

            mockMvc.perform(get("/api/v1/me/attendance/statistics/term")
                            .param("teamId", TEAM_ID.toString())
                            .param("from", "2026-04-01")
                            .param("to", "2026-04-30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.studentUserId").value(USER_ID))
                    .andExpect(jsonPath("$.data.totalSchoolDays").value(20))
                    .andExpect(jsonPath("$.data.attendanceRate").value(90.00));
        }
    }

    // ════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/attendance/export
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/attendance/export")
    class ExportCsv {

        @Test
        @DisplayName("正常系: CSV バイト配列を返す → 200 + text/csv + ヘッダー行")
        void 正常系_200_CSV() throws Exception {
            String csvContent = "studentUserId,attendanceDate,status,absenceReason,arrivalTime,leaveTime,comment\n"
                    + "1,2026-05-01,ABSENT,,,\n";
            given(statisticsService.exportAttendanceCsv(
                    eq(TEAM_ID),
                    eq(LocalDate.of(2026, 5, 1)),
                    eq(LocalDate.of(2026, 5, 31))))
                    .willReturn(csvContent.getBytes(StandardCharsets.UTF_8));

            byte[] body = mockMvc.perform(get("/api/v1/teams/{teamId}/attendance/export", TEAM_ID)
                            .param("from", "2026-05-01")
                            .param("to", "2026-05-31"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/csv; charset=utf-8"))
                    .andReturn().getResponse().getContentAsByteArray();

            assertThat(new String(body, StandardCharsets.UTF_8))
                    .startsWith("studentUserId,attendanceDate,status");
        }
    }
}
