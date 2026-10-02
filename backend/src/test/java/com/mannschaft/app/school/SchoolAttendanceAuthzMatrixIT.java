package com.mannschaft.app.school;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 学校出欠の認可是正 第1段 — 認可行列 IT（試練・red）。
 *
 * <p>対象 EP 24 本（plan の EP 20 本 ＋ 改訂 AC-14 の保護者連絡一覧・確認・反映・アラート解決の一部）を、
 * ロール行列 {@link Actor}（18 役）に掛ける。実 Security 設定の下で {@code @SpringBootTest} ＋ MockMvc ＋
 * Testcontainers MySQL。自分の Bean・DB・認可はモックしない。
 * 未認証 401 は {@code addFilters=false} では検証できないため {@code SchoolAttendanceUnauthenticatedIT}
 * （フィルタ有効）が受け持つ。</p>
 *
 * <p>期待値は {@link SchoolAttendanceAuthzFixture} の判定（V/R/P）に従う。許可は各 EP の成功ステータス、
 * 不許可は 403（COMMON_002）、bare-id の評価系 2 EP のみ 404。403 の本文には生徒情報が一切含まれない
 * （AC-3）。各ケースの表示名に対応する AC 番号を含める。</p>
 *
 * <p>対応する AC: AC-1（daily 一覧）/ AC-2（時限一覧・候補）/ AC-3（クラス全員を返す 6EP）/
 * AC-4（生徒単位閲覧 3EP）/ AC-6（越境: 別クラス担任・別テナント ADMIN の行）/ AC-8（日次登録）/
 * AC-9（日次修正）/ AC-10（時限登録）/ AC-11（時限修正）/ AC-13（兄弟の書込 4EP）/
 * AC-14（連絡一覧・確認・反映・アラート解決）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("学校出欠 認可行列 IT（第1段）")
class SchoolAttendanceAuthzMatrixIT extends SchoolAttendanceAuthzFixture {

    @BeforeEach
    void setUp() {
        seedWorld();
    }

    /** 検証対象 EP。許可者集合・成功ステータス・不許可ステータス・リクエストの組み立てを持つ。 */
    enum Ep {
        // ── 閲覧（クラス全体）──
        DAILY_LIST("AC-1 GET daily 一覧", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/daily", t.teamAId).param("date", t.date.toString())),
        PERIOD_LIST("AC-2 GET periods 一覧", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/periods", t.teamAId)
                        .param("date", t.date.toString()).param("periodNumber", "1")),
        PERIOD_CANDIDATES("AC-2 GET periods/{n}/candidates", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/periods/1/candidates", t.teamAId)
                        .param("date", t.date.toString())),
        ALERT_LIST("AC-3 GET transition-alerts", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/transition-alerts", t.teamAId)
                        .param("date", t.date.toString())),
        STATS_MONTHLY("AC-3 GET statistics/monthly", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/statistics/monthly", t.teamAId)
                        .param("year", "2025").param("month", "4")),
        EXPORT_CSV("AC-3 GET export(CSV)", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/export", t.teamAId)
                        .param("from", STAT_FROM.toString()).param("to", STAT_TO.toString())),
        SUMMARIES("AC-3 GET summaries", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/summaries", t.teamAId)
                        .param("academicYear", String.valueOf(ACADEMIC_YEAR))),
        AT_RISK("AC-3 GET requirements/at-risk", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/requirements/at-risk", t.teamAId)),
        LOCATIONS_LIST("AC-3 GET locations", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/locations", t.teamAId)
                        .param("date", t.date.toString())),
        NOTICE_LIST("AC-14 GET notices 一覧", VIEW, 200, 403,
                t -> get("/api/v1/teams/{t}/attendance/notices", t.teamAId)
                        .param("date", t.date.toString())),

        // ── 生徒単位の閲覧（本人・保護者・V）──
        SUMMARY_STUDENT("AC-4 GET students/{s}/attendance/summary", STUDENT_VIEW, 200, 403,
                t -> get("/api/v1/students/{s}/attendance/summary", t.studentAId)
                        .param("teamId", String.valueOf(t.teamAId))
                        .param("academicYear", String.valueOf(ACADEMIC_YEAR))),
        LOCATION_TIMELINE("AC-4 GET students/{s}/locations/timeline", STUDENT_VIEW, 200, 403,
                t -> get("/api/v1/students/{s}/attendance/locations/timeline", t.studentAId)
                        .param("date", t.date.toString())),
        EVALUATIONS_STUDENT("AC-4 GET students/{s}/requirements/evaluations", STUDENT_VIEW, 200, 403,
                t -> get("/api/v1/students/{s}/attendance/requirements/evaluations", t.studentAId)),

        // ── 登録・修正（R/P）──
        DAILY_ROLLCALL("AC-8 POST daily/roll-call", REGISTER, 201, 403,
                t -> post("/api/v1/teams/{t}/attendance/daily/roll-call", t.teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(t.json(t.entriesBody(t.date.plusDays(1), t.entry(t.studentAId, "ATTENDING"))))),
        DAILY_PATCH("AC-9 PATCH daily/{recordId}", REGISTER, 200, 403,
                t -> patch("/api/v1/teams/{t}/attendance/daily/{r}", t.teamAId, t.dailyAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(t.json(Map.of("comment", "SAZ修正")))),
        PERIOD_POST("AC-10 POST periods/{n}", REGISTER, 201, 403,
                t -> post("/api/v1/teams/{t}/attendance/periods/2", t.teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(t.json(t.entriesBody(t.date, t.entry(t.studentAId, "ATTENDING"))))),
        PERIOD_PATCH("AC-11 PATCH periods/{recordId}", REGISTER, 200, 403,
                t -> patch("/api/v1/teams/{t}/attendance/periods/{r}", t.teamAId, t.periodAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(t.json(Map.of("comment", "SAZ修正")))),

        // ── 兄弟の書込（AC-13）──
        SUMMARY_RECALCULATE("AC-13 POST summary/recalculate", REGISTER, 201, 403,
                t -> post("/api/v1/students/{s}/attendance/summary/recalculate", t.studentAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(t.json(t.recalcBody()))),
        LOCATION_CHANGE("AC-13 POST locations/changes", REGISTER, 201, 403,
                t -> post("/api/v1/teams/{t}/attendance/locations/changes", t.teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(t.json(t.locationChangeBody()))),
        EVALUATE("AC-13 POST requirements/{ruleId}/evaluate（bare-id は 404）", REGISTER, 201, 404,
                t -> post("/api/v1/students/{s}/attendance/requirements/{rule}/evaluate", t.studentAId, t.ruleAId)),
        RESOLVE_EVALUATION("AC-13 POST evaluations/{id}/resolve（bare-id は 404）", REGISTER, 200, 404,
                t -> post("/api/v1/attendance/requirements/evaluations/{e}/resolve", t.evalAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(t.json(Map.of("resolutionNote", "SAZ面談で指導完了")))),

        // ── 保護者連絡・アラート（AC-14: 担任も可・一般 MEMBER は不可）──
        NOTICE_ACK("AC-14 POST notices/{id}/acknowledge", REGISTER, 200, 403,
                t -> post("/api/v1/teams/{t}/attendance/notices/{n}/acknowledge", t.teamAId, t.noticeAId)),
        NOTICE_APPLY("AC-14 POST notices/{id}/apply", REGISTER, 200, 403,
                t -> post("/api/v1/teams/{t}/attendance/notices/{n}/apply", t.teamAId, t.noticeAId)),
        ALERT_RESOLVE("AC-14 POST transition-alerts/{id}/resolve", REGISTER, 200, 403,
                t -> post("/api/v1/teams/{t}/attendance/transition-alerts/{a}/resolve", t.teamAId, t.alertAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(t.json(Map.of("note", "SAZ保護者へ連絡済み"))));

        final String label;
        final Set<Actor> allowed;
        final int okStatus;
        final int deniedStatus;
        final Function<SchoolAttendanceAuthzMatrixIT, MockHttpServletRequestBuilder> request;

        Ep(String label, Set<Actor> allowed, int okStatus, int deniedStatus,
           Function<SchoolAttendanceAuthzMatrixIT, MockHttpServletRequestBuilder> request) {
            this.label = label;
            this.allowed = allowed;
            this.okStatus = okStatus;
            this.deniedStatus = deniedStatus;
            this.request = request;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Arguments> cases() {
        Stream.Builder<Arguments> b = Stream.builder();
        for (Ep ep : Ep.values()) {
            for (Actor actor : Actor.values()) {
                b.add(Arguments.of(ep, actor));
            }
        }
        return b.build();
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("cases")
    @DisplayName("EP × ロール行列: 許可者は成功・それ以外は 403（bare-id は 404）")
    void 認可行列(Ep ep, Actor actor) throws Exception {
        auth(actor);

        MvcResult result = mockMvc.perform(ep.request.apply(this)).andReturn();

        boolean allowed = ep.allowed.contains(actor);
        int expected = allowed ? ep.okStatus : ep.deniedStatus;
        assertThat(result.getResponse().getStatus())
                .as("%s を %s が叩いた結果（許可=%s）", ep.label, actor, allowed)
                .isEqualTo(expected);

        if (!allowed) {
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            // 403/404 の本文に生徒情報が一切含まれない（AC-3）。空配列 200 で認可を代用しない。
            assertThat(body).as("拒否応答に生徒情報を含めない").doesNotContain("SAZ").doesNotContain("studentUserId");
            if (ep.deniedStatus == 403) {
                JsonNode json = objectMapper.readTree(body);
                assertThat(json.path("error").path("code").asText())
                        .as("拒否は COMMON_002 に統一する")
                        .isEqualTo("COMMON_002");
            }
        }
    }

    // ---- ボディ補助 ----

    Map<String, Object> recalcBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamAId);
        body.put("academicYear", ACADEMIC_YEAR);
        body.put("periodFrom", STAT_FROM.toString());
        body.put("periodTo", STAT_TO.toString());
        return body;
    }

    Map<String, Object> locationChangeBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("studentUserId", studentAId);
        body.put("attendanceDate", date.toString());
        body.put("fromLocation", "CLASSROOM");
        body.put("toLocation", "SICK_BAY");
        body.put("reason", "FELT_SICK");
        return body;
    }
}
