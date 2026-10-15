package com.mannschaft.app.school;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 学校出欠の認可是正 第1段 — 担任の現役判定・存在オラクル・判定結果 API（試練・red）。
 *
 * <p>対応 AC: AC-5（現役判定の境界）/ AC-7（存在オラクル不在）/ AC-23（Policy の判定結果を FE へ返す BE API）。</p>
 *
 * <h2>試練が決めた契約（出陣はこの契約に合わせて実装する。変えたい場合は殿に諮ること）</h2>
 * <ol>
 *   <li><b>現役の定義</b>: {@code effective_from <= 今日 && (effective_until IS NULL || 今日 <= effective_until)}
 *       （開始日当日・終了日当日はいずれも現役。終了日の翌日・開始日の前日は現役でない）。
 *       「今日」は学校の業務ゾーン（Asia/Tokyo）の日付。</li>
 *   <li><b>複数行の選択規則</b>: 現役の行が複数あるときは<b>全ての現役行が資格を与える（和集合）</b>。
 *       行の選択（最新優先など）はしない。無期限行が年度をまたいで複数残っていても、それぞれの担任・副担任が現役扱い。
 *       現役でない行（過去・未来）の担任・副担任は一切資格を持たない。</li>
 *   <li><b>assistant_teacher_user_ids の不正値</b>: 数値 ID の配列以外（SQL NULL・{@code []}・オブジェクト・文字列・
 *       null 要素・非数値要素）は<b>副担任資格だけを無効化</b>し、例外にせず、主担任本人の資格は保つ。
 *       （DB は JSON 型なので「JSON として壊れた文字列」は格納できない。JSON として有効だが形が不正な値を使う。）</li>
 *   <li><b>判定結果 API（AC-23）</b>: {@code GET /api/v1/teams/{teamId}/attendance/permissions}。
 *       認証済みなら常に 200 で
 *       {@code {"data":{"teamId":N,"canView":bool,"canRecordDaily":bool,"canRecordPeriod":bool}}} を返す。
 *       権限が無い者・非所属者・存在しないチームでも 403/404 にせず全項目 false の 200
 *       （FE が画面の入口とボタンを出し分けるための API であり、チームの存在有無を応答差にしない）。
 *       canView=V、canRecordDaily=R、canRecordPeriod=P（第1段は R と同じ）。未認証は 401。
 *       FE は独自判定をせず、この結果だけで出し分ける。</li>
 *   <li><b>存在オラクル（AC-7）</b>: 存在しない teamId・他テナントの teamId・自分が非教員の既存 teamId は
 *       HTTP ステータス・error.code・error.message が同一（403 COMMON_002）。
 *       認可通過後の recordId 越境は、存在しない recordId と同一応答（404）。</li>
 * </ol>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("学校出欠 担任の現役判定・存在オラクル・判定結果API（第1段）")
class SchoolAttendanceHomeroomPolicyIT extends SchoolAttendanceAuthzFixture {

    @BeforeEach
    void setUp() {
        seedWorld();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5 現役判定
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> activeWindowCases() {
        return Stream.of(
                Arguments.of("開始日当日は現役", 0, null, true),
                Arguments.of("開始日の前日（予定担任）は現役でない", 1, null, false),
                Arguments.of("終了日当日は現役", -10, 0, true),
                Arguments.of("終了日の前日までに終わる行も、終了日が明日なら現役", -10, 1, true),
                Arguments.of("終了日の翌日（元担任）は現役でない", -10, -1, false),
                Arguments.of("無期限・過去開始は現役", -200, null, true));
    }

    @ParameterizedTest(name = "AC-5 担任: {0}")
    @MethodSource("activeWindowCases")
    @DisplayName("AC-5: 担任の現役判定は開始日当日・終了日当日を含み、終了日翌日・開始日前日を含まない")
    void 担任の現役判定の境界(String label, int fromOffset, Integer untilOffset, boolean allowed) throws Exception {
        Long team = insertTeam("SAZ境界担任");
        Long teacher = newUser("boundary-teacher");
        addMember(teacher, team);
        addHomeroom(team, teacher, null, date.plusDays(fromOffset),
                untilOffset == null ? null : date.plusDays(untilOffset));

        auth(teacher);
        mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", team).param("date", date.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is(allowed ? 200 : 403));
    }

    @ParameterizedTest(name = "AC-5 副担任: {0}")
    @MethodSource("activeWindowCases")
    @DisplayName("AC-5: 副担任（assistant JSON）にも同じ現役判定が適用される")
    void 副担任の現役判定の境界(String label, int fromOffset, Integer untilOffset, boolean allowed) throws Exception {
        Long team = insertTeam("SAZ境界副担任");
        Long main = newUser("boundary-main");
        Long assistant = newUser("boundary-assistant");
        addMember(main, team);
        addMember(assistant, team);
        addHomeroom(team, main, "[" + assistant + "]", date.plusDays(fromOffset),
                untilOffset == null ? null : date.plusDays(untilOffset));

        auth(assistant);
        mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", team).param("date", date.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is(allowed ? 200 : 403));
    }

    @Test
    @DisplayName("AC-5: 担任交代当日は新担任が 200・旧担任が 403（履歴行が複数あっても現役行のみで判定）")
    void 担任交代当日は新担任のみ許可() throws Exception {
        Long team = insertTeam("SAZ交代");
        Long oldTeacher = newUser("old-teacher");
        Long newTeacher = newUser("new-teacher");
        addMember(oldTeacher, team);
        addMember(newTeacher, team);
        addHomeroom(team, oldTeacher, null, date.minusDays(100), date.minusDays(1));
        addHomeroom(team, newTeacher, null, date, null);

        auth(newTeacher);
        mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", team).param("date", date.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        auth(oldTeacher);
        mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", team).param("date", date.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    @DisplayName("AC-5: 無期限の現役行が複数あるときは全ての現役行の担任・副担任が資格を持つ（和集合）")
    void 無期限行が複数あれば和集合() throws Exception {
        Long team = insertTeam("SAZ複数無期限");
        Long teacherX = newUser("open-x");
        Long teacherY = newUser("open-y");
        Long assistantY = newUser("open-y-assistant");
        for (Long u : new Long[]{teacherX, teacherY, assistantY}) {
            addMember(u, team);
        }
        addHomeroom(team, teacherX, null, date.minusDays(400), null);
        addHomeroom(team, teacherY, "[" + assistantY + "]", date.minusDays(30), null);

        for (Long u : new Long[]{teacherX, teacherY, assistantY}) {
            auth(u);
            mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", team).param("date", date.toString()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
    }

    @Test
    @DisplayName("AC-5: 現役でない行の副担任は資格を持たない（過去行・未来行）")
    void 現役でない行の副担任は403() throws Exception {
        Long team = insertTeam("SAZ非現役副担任");
        Long main = newUser("inactive-main");
        Long pastAssistant = newUser("past-assistant");
        Long futureAssistant = newUser("future-assistant");
        for (Long u : new Long[]{main, pastAssistant, futureAssistant}) {
            addMember(u, team);
        }
        addHomeroom(team, main, null, date.minusDays(30), null);
        addHomeroom(team, newUser("old-main"), "[" + pastAssistant + "]", date.minusDays(100), date.minusDays(1));
        addHomeroom(team, newUser("next-main"), "[" + futureAssistant + "]", date.plusDays(1), null);

        for (Long u : new Long[]{pastAssistant, futureAssistant}) {
            auth(u);
            mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", team).param("date", date.toString()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
    }

    static Stream<Arguments> malformedAssistantJson() {
        return Stream.of(
                Arguments.of("SQL NULL", null),
                Arguments.of("空配列", "[]"),
                Arguments.of("オブジェクト", "{\"x\":1}"),
                Arguments.of("JSON 文字列", "\"abc\""),
                Arguments.of("null 要素", "[null]"),
                Arguments.of("非数値要素", "[\"abc\"]"),
                Arguments.of("小数", "[1.5]"),
                Arguments.of("桁あふれ", "[99999999999999999999]"),
                Arguments.of("JSON null リテラル", "null"));
    }

    @ParameterizedTest(name = "AC-5 副担任JSON不正: {0}")
    @MethodSource("malformedAssistantJson")
    @DisplayName("AC-5: assistant JSON が不正でも例外にならず、主担任本人は許可・無関係の MEMBER は 403")
    void 副担任JSONが不正でも主担任は許可される(String label, String assistantJson) throws Exception {
        Long team = insertTeam("SAZ不正JSON");
        Long main = newUser("json-main");
        Long bystander = newUser("json-bystander");
        addMember(main, team);
        addMember(bystander, team);
        addHomeroom(team, main, assistantJson, date.minusDays(30), null);

        auth(main);
        mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", team).param("date", date.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        auth(bystander);
        mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", team).param("date", date.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7 存在オラクル不在
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-7: 存在しない teamId・他テナントの teamId・自分が非教員の既存 teamId は 403 COMMON_002 で同一応答")
    void 存在オラクルが無い() throws Exception {
        auth(Actor.PLAIN_MEMBER);

        JsonNode nonexistent = deniedBody(get("/api/v1/teams/{t}/attendance/daily", 999_999_999L)
                .param("date", date.toString()));
        JsonNode otherTenant = deniedBody(get("/api/v1/teams/{t}/attendance/daily", teamCId)
                .param("date", date.toString()));
        JsonNode ownNonTeacher = deniedBody(get("/api/v1/teams/{t}/attendance/daily", teamAId)
                .param("date", date.toString()));

        assertThat(nonexistent.path("error").path("code").asText()).isEqualTo("COMMON_002");
        for (JsonNode other : new JsonNode[]{otherTenant, ownNonTeacher}) {
            assertThat(other.path("error").path("code").asText())
                    .isEqualTo(nonexistent.path("error").path("code").asText());
            assertThat(other.path("error").path("message").asText())
                    .isEqualTo(nonexistent.path("error").path("message").asText());
        }
    }

    private JsonNode deniedBody(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        MvcResult r = mockMvc.perform(req).andReturn();
        assertThat(r.getResponse().getStatus()).as("拒否は 403 に統一").isEqualTo(403);
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("AC-7: 認可通過後の recordId 越境は、存在しない recordId と同一の 404 応答")
    void recordId越境は存在しないIDと同一応答() throws Exception {
        auth(Actor.HOMEROOM);
        Map<String, Object> body = Map.of("comment", "SAZ越境");

        MvcResult crossClass = mockMvc.perform(patch("/api/v1/teams/{t}/attendance/daily/{r}", teamAId, dailyBId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();
        MvcResult missing = mockMvc.perform(patch("/api/v1/teams/{t}/attendance/daily/{r}", teamAId, 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();

        assertThat(crossClass.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        JsonNode a = objectMapper.readTree(crossClass.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode b = objectMapper.readTree(missing.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(a.path("error").path("code").asText()).isEqualTo(b.path("error").path("code").asText());
        assertThat(a.path("error").path("message").asText()).isEqualTo(b.path("error").path("message").asText());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-23 判定結果 API
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-23 permissions API × {0}")
    @EnumSource(Actor.class)
    @DisplayName("AC-23: 判定結果 API は Policy と同じ規則で canView/canRecordDaily/canRecordPeriod を返す")
    void 判定結果APIはPolicyと一致する(Actor actor) throws Exception {
        auth(actor);

        MvcResult result = mockMvc.perform(get("/api/v1/teams/{t}/attendance/permissions", teamAId)).andReturn();

        assertThat(result.getResponse().getStatus()).as("認証済みなら常に 200").isEqualTo(200);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        assertThat(data.path("teamId").asLong()).isEqualTo(teamAId);
        assertThat(data.path("canView").asBoolean(false)).as("canView（V）").isEqualTo(VIEW.contains(actor));
        assertThat(data.path("canRecordDaily").asBoolean(false)).as("canRecordDaily（R）")
                .isEqualTo(REGISTER.contains(actor));
        assertThat(data.path("canRecordPeriod").asBoolean(false)).as("canRecordPeriod（P＝第1段は R）")
                .isEqualTo(REGISTER.contains(actor));
    }

    @Test
    @DisplayName("AC-23: 存在しないチーム・他テナントのチームでも 403/404 にせず全項目 false の 200")
    void 存在しないチームでも全てfalse() throws Exception {
        auth(Actor.HOMEROOM);
        for (Long team : new Long[]{999_999_999L, teamCId, teamBId}) {
            MvcResult result = mockMvc.perform(get("/api/v1/teams/{t}/attendance/permissions", team)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("data");
            assertThat(data.path("canView").asBoolean(true)).isFalse();
            assertThat(data.path("canRecordDaily").asBoolean(true)).isFalse();
            assertThat(data.path("canRecordPeriod").asBoolean(true)).isFalse();
        }
    }

    @Test
    @DisplayName("AC-23: 現役でなくなった担任は判定結果 API でも全項目 false（Policy と単一の判定源）")
    void 元担任は全てfalse() throws Exception {
        LocalDate yesterday = date.minusDays(1);
        Long team = insertTeam("SAZ判定結果");
        Long former = newUser("perm-former");
        addMember(former, team);
        addHomeroom(team, former, null, date.minusDays(100), yesterday);

        auth(former);
        MvcResult result = mockMvc.perform(get("/api/v1/teams/{t}/attendance/permissions", team)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        assertThat(data.path("canView").asBoolean(true)).isFalse();
        assertThat(data.path("canRecordDaily").asBoolean(true)).isFalse();
    }
}
