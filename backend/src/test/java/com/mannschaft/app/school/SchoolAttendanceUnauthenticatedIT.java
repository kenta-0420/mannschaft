package com.mannschaft.app.school;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 学校出欠の認可是正 第1段 — 未認証は 401（実 Security フィルタ有効）（試練・red）。
 *
 * <p>対応 AC: AC-1 の「未認証は 401」を、AC-1〜14 の全 EP と判定結果 API（AC-23）に広げて固定する。
 * 認可行列 IT は {@code addFilters=false}（SecurityContext を手で積む）ため 401 を観測できない。
 * 本クラスは {@code @AutoConfigureMockMvc}（フィルタ有効）で、認証情報を一切付けずに叩く。
 * 認証より先に DB や Service に到達しないので、パスの ID は存在しなくてよい。</p>
 *
 * <p>陽性対照: 同じ構成で認証済み（自己スコープの {@code /me/attendance/daily}）は 401 にならない。
 * 「全部 401」を返す雑な構成では対照が緑にならず見抜ける。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("学校出欠 未認証は 401（第1段・フィルタ有効）")
class SchoolAttendanceUnauthenticatedIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> endpoints() {
        String t = "/api/v1/teams/1/attendance";
        String s = "/api/v1/students/1/attendance";
        return Stream.of(
                Arguments.of("AC-1 GET daily", HttpMethod.GET, t + "/daily?date=2026-01-01"),
                Arguments.of("AC-8 POST daily/roll-call", HttpMethod.POST, t + "/daily/roll-call"),
                Arguments.of("AC-9 PATCH daily/{id}", HttpMethod.PATCH, t + "/daily/1"),
                Arguments.of("AC-2 GET periods", HttpMethod.GET, t + "/periods?date=2026-01-01&periodNumber=1"),
                Arguments.of("AC-2 GET periods/{n}/candidates", HttpMethod.GET,
                        t + "/periods/1/candidates?date=2026-01-01"),
                Arguments.of("AC-10 POST periods/{n}", HttpMethod.POST, t + "/periods/1"),
                Arguments.of("AC-11 PATCH periods/{id}", HttpMethod.PATCH, t + "/periods/1"),
                Arguments.of("AC-3 GET transition-alerts", HttpMethod.GET,
                        t + "/transition-alerts?date=2026-01-01"),
                Arguments.of("AC-14 POST transition-alerts/{id}/resolve", HttpMethod.POST,
                        t + "/transition-alerts/1/resolve"),
                Arguments.of("AC-3 GET statistics/monthly", HttpMethod.GET,
                        t + "/statistics/monthly?year=2026&month=1"),
                Arguments.of("AC-3 GET export", HttpMethod.GET, t + "/export?from=2026-01-01&to=2026-01-31"),
                Arguments.of("AC-3 GET summaries", HttpMethod.GET, t + "/summaries?academicYear=2026"),
                Arguments.of("AC-3 GET requirements/at-risk", HttpMethod.GET, t + "/requirements/at-risk"),
                Arguments.of("AC-3 GET locations", HttpMethod.GET, t + "/locations?date=2026-01-01"),
                Arguments.of("AC-13 POST locations/changes", HttpMethod.POST, t + "/locations/changes"),
                Arguments.of("AC-14 GET notices", HttpMethod.GET, t + "/notices?date=2026-01-01"),
                Arguments.of("AC-14 POST notices/{id}/acknowledge", HttpMethod.POST, t + "/notices/1/acknowledge"),
                Arguments.of("AC-14 POST notices/{id}/apply", HttpMethod.POST, t + "/notices/1/apply"),
                Arguments.of("AC-4 GET students/{s}/summary", HttpMethod.GET,
                        s + "/summary?teamId=1&academicYear=2026"),
                Arguments.of("AC-13 POST students/{s}/summary/recalculate", HttpMethod.POST,
                        s + "/summary/recalculate"),
                Arguments.of("AC-4 GET students/{s}/locations/timeline", HttpMethod.GET,
                        s + "/locations/timeline?date=2026-01-01"),
                Arguments.of("AC-4 GET students/{s}/requirements/evaluations", HttpMethod.GET,
                        s + "/requirements/evaluations"),
                Arguments.of("AC-13 POST requirements/{r}/evaluate", HttpMethod.POST,
                        s + "/requirements/1/evaluate"),
                Arguments.of("AC-13 POST evaluations/{id}/resolve", HttpMethod.POST,
                        "/api/v1/attendance/requirements/evaluations/1/resolve"),
                Arguments.of("AC-23 GET permissions（判定結果 API）", HttpMethod.GET, t + "/permissions"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("未認証の要求は 401")
    void 未認証は401(String label, HttpMethod method, String url) throws Exception {
        MvcResult result = mockMvc.perform(request(method, url)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("%s は未認証で 401", label).isEqualTo(401);
    }

    @Test
    @DisplayName("陽性対照: 認証済みの自己スコープ要求（/me/attendance/daily）は 401 にならない")
    void 認証済みは401にならない() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/attendance/daily")
                        .param("from", "2026-01-01").param("to", "2026-01-31")
                        .with(user("1")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }
}
