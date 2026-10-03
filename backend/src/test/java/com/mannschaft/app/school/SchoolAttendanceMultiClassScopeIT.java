package com.mannschaft.app.school;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 学校出欠の認可是正 第1段 — 兼籍生徒のクラス分離・組織スコープ規程・本人経路の非回帰（試練・red）。
 *
 * <p>対応 AC: AC-4（閲覧許可と返却範囲を分ける）/ AC-21（兼籍生徒でクラス A の操作がクラス B を汚さない）/
 * AC-22（組織スコープ規程の評価・解消の認可）/ AC-15（本人経路の非回帰・担任の主経路の完走）。</p>
 *
 * <h2>試練が決めた契約</h2>
 * <ul>
 *   <li><b>返却範囲（AC-4）</b>: クラス A のみで権限を持つ教員には、同じ生徒のクラス B の場所履歴・評価は返らない
 *       （取得順序・登録順序に依存しない）。生徒本人とケアリンクのある保護者は、自分（の子）の全クラス分を返す。</li>
 *   <li><b>兼籍（AC-21）</b>: クラス A での場所変更・集計再計算は、クラス B の日次・時限記録を変更せず、
 *       A の集計に B の時限記録を含めない。</li>
 *   <li><b>組織スコープ規程（AC-22）</b>: 規程の organizationId が非 null（teamId が null）のとき、
 *       評価（evaluate）・解消（resolve）できるのは<b>その組織の ADMIN/DEPUTY_ADMIN のみ</b>。
 *       チームの担任・チーム ADMIN・組織の一般 MEMBER・別組織の ADMIN・SYSTEM_ADMIN は bare-id の 404 に畳む。
 *       チーム Policy に null の teamId を渡さない。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("学校出欠 兼籍生徒・組織スコープ規程・本人経路（第1段）")
class SchoolAttendanceMultiClassScopeIT extends SchoolAttendanceAuthzFixture {

    private Long periodADualId;

    @BeforeEach
    void setUp() {
        seedWorld();
        Long homeroomA = actors.get(Actor.HOMEROOM);
        // 兼籍の生徒 D（A・B の両方に所属）の A 側の日次・時限を足す（B 側は seedWorld が作成済み）。
        insertDaily(teamAId, dualStudentId, date, AttendanceStatus.ATTENDING, homeroomA);
        periodADualId = insertPeriod(teamAId, dualStudentId, date, 1, AttendanceStatus.ATTENDING, homeroomA);
        em.flush();
        em.clear();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private List<Long> longs(JsonNode array, String field) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(field).asLong()));
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4 返却範囲（場所履歴）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-4 場所履歴の返却範囲（B の履歴を先に登録={0}）")
    @ValueSource(booleans = {false, true})
    @DisplayName("AC-4: クラス A のみの教員にはクラス B の場所履歴が返らない。本人には全クラス分が返る")
    void 場所履歴はクラスの範囲だけ返る(boolean bFirst) throws Exception {
        Long homeroomA = actors.get(Actor.HOMEROOM);
        Long homeroomB = actors.get(Actor.OTHER_CLASS_HOMEROOM);
        if (bFirst) {
            insertLocationChange(teamBId, dualStudentId, date, homeroomB);
            insertLocationChange(teamAId, dualStudentId, date, homeroomA);
        } else {
            insertLocationChange(teamAId, dualStudentId, date, homeroomA);
            insertLocationChange(teamBId, dualStudentId, date, homeroomB);
        }
        em.flush();
        em.clear();
        String url = "/api/v1/students/{s}/attendance/locations/timeline";

        auth(Actor.HOMEROOM);
        MvcResult forA = mockMvc.perform(get(url, dualStudentId).param("date", date.toString())).andReturn();
        assertThat(forA.getResponse().getStatus()).isEqualTo(200);
        assertThat(longs(data(forA).path("changes"), "teamId")).as("クラス A の教員に B の履歴は返らない")
                .containsOnly(teamAId).hasSize(1);

        auth(Actor.OTHER_CLASS_HOMEROOM);
        MvcResult forB = mockMvc.perform(get(url, dualStudentId).param("date", date.toString())).andReturn();
        assertThat(forB.getResponse().getStatus()).isEqualTo(200);
        assertThat(longs(data(forB).path("changes"), "teamId")).containsOnly(teamBId).hasSize(1);

        auth(dualStudentId);
        MvcResult self = mockMvc.perform(get(url, dualStudentId).param("date", date.toString())).andReturn();
        assertThat(self.getResponse().getStatus()).isEqualTo(200);
        assertThat(longs(data(self).path("changes"), "teamId")).as("本人には全クラス分が返る")
                .containsExactlyInAnyOrder(teamAId, teamBId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4 返却範囲（評価一覧）・評価 0 件の教員
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-4 評価一覧の返却範囲（B の評価を先に登録={0}）")
    @ValueSource(booleans = {false, true})
    @DisplayName("AC-4: クラス A のみの教員にはクラス B の規程の評価が返らない。本人には全クラス分が返る")
    void 評価一覧はクラスの範囲だけ返る(boolean bFirst) throws Exception {
        Long summaryA = insertSummary(teamAId, dualStudentId);
        Long summaryB = insertSummary(teamBId, dualStudentId);
        if (bFirst) {
            insertEvaluation(ruleBId, dualStudentId, summaryB);
            insertEvaluation(ruleAId, dualStudentId, summaryA);
        } else {
            insertEvaluation(ruleAId, dualStudentId, summaryA);
            insertEvaluation(ruleBId, dualStudentId, summaryB);
        }
        em.flush();
        em.clear();
        String url = "/api/v1/students/{s}/attendance/requirements/evaluations";

        auth(Actor.HOMEROOM);
        MvcResult forA = mockMvc.perform(get(url, dualStudentId)).andReturn();
        assertThat(forA.getResponse().getStatus()).isEqualTo(200);
        assertThat(longs(data(forA), "requirementRuleId")).as("クラス A の教員に B の規程の評価は返らない")
                .containsOnly(ruleAId);

        auth(Actor.OTHER_CLASS_HOMEROOM);
        MvcResult forB = mockMvc.perform(get(url, dualStudentId)).andReturn();
        assertThat(forB.getResponse().getStatus()).isEqualTo(200);
        assertThat(longs(data(forB), "requirementRuleId")).containsOnly(ruleBId);

        auth(dualStudentId);
        MvcResult self = mockMvc.perform(get(url, dualStudentId)).andReturn();
        assertThat(self.getResponse().getStatus()).isEqualTo(200);
        assertThat(longs(data(self), "requirementRuleId")).as("本人には全クラス分が返る")
                .containsExactlyInAnyOrder(ruleAId, ruleBId);
    }

    @Test
    @DisplayName("AC-4: 評価が 0 件の生徒でも、担任（V）は 403 に落ちず 200 の空配列を得る")
    void 評価0件でも教員は200の空配列() throws Exception {
        auth(Actor.HOMEROOM);

        MvcResult result = mockMvc.perform(get("/api/v1/students/{s}/attendance/requirements/evaluations",
                classmateId)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(result).isArray()).isTrue();
        assertThat(data(result)).isEmpty();
    }

    @Test
    @DisplayName("AC-4/AC-6: 教員 A は teamId クエリを他クラス B へ差し替えても兼籍生徒の B の集計を取得できない")
    void 集計はteamIdを差し替えても他クラスは403() throws Exception {
        insertSummary(teamAId, dualStudentId);
        insertSummary(teamBId, dualStudentId);
        em.flush();
        em.clear();
        auth(Actor.HOMEROOM);

        MvcResult own = mockMvc.perform(get("/api/v1/students/{s}/attendance/summary", dualStudentId)
                .param("teamId", String.valueOf(teamAId))
                .param("academicYear", String.valueOf(ACADEMIC_YEAR))).andReturn();
        MvcResult other = mockMvc.perform(get("/api/v1/students/{s}/attendance/summary", dualStudentId)
                .param("teamId", String.valueOf(teamBId))
                .param("academicYear", String.valueOf(ACADEMIC_YEAR))).andReturn();

        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(other.getResponse().getStatus()).isEqualTo(403);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-21 兼籍生徒
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-21: クラス A で場所変更しても、クラス B の日次・時限記録の場所は変わらない")
    void 場所変更は他クラスの記録を変えない() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("studentUserId", dualStudentId);
        body.put("attendanceDate", date.toString());
        body.put("fromLocation", "CLASSROOM");
        body.put("toLocation", "SICK_BAY");
        body.put("changedAtPeriod", 1);
        body.put("reason", "FELT_SICK");
        auth(Actor.HOMEROOM);

        MvcResult result = mockMvc.perform(post("/api/v1/teams/{t}/attendance/locations/changes", teamAId)
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(text("SELECT attendance_location FROM period_attendance_records WHERE id = ?1", periodADualId))
                .as("クラス A の時限記録は更新される").isEqualTo("SICK_BAY");
        assertThat(text("SELECT attendance_location FROM period_attendance_records WHERE id = ?1", periodBId))
                .as("クラス B の時限記録は不変").isEqualTo("CLASSROOM");
        assertThat(text("SELECT attendance_location FROM daily_attendance_records WHERE id = ?1", dailyBId))
                .as("クラス B の日次記録は不変").isEqualTo("CLASSROOM");
    }

    @Test
    @DisplayName("AC-21: クラス A の集計再計算に、クラス B の時限記録は含まれない")
    void 再計算は他クラスの時限記録を含めない() throws Exception {
        // B 側に時限 2〜4 の欠席を足す。A 側は 1 時限目の出席 1 件のみ。
        Long homeroomB = actors.get(Actor.OTHER_CLASS_HOMEROOM);
        for (int p = 2; p <= 4; p++) {
            insertPeriod(teamBId, dualStudentId, date, p, AttendanceStatus.ABSENT, homeroomB);
        }
        em.flush();
        em.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamAId);
        body.put("academicYear", ACADEMIC_YEAR);
        body.put("periodFrom", date.minusDays(1).toString());
        body.put("periodTo", date.plusDays(1).toString());
        auth(Actor.HOMEROOM);

        MvcResult result = mockMvc.perform(post("/api/v1/students/{s}/attendance/summary/recalculate", dualStudentId)
                .contentType(MediaType.APPLICATION_JSON).content(json(body))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode summary = data(result).path("summary");
        assertThat(summary.path("totalPeriods").asInt()).as("A の時限記録 1 件のみ").isEqualTo(1);
        assertThat(summary.path("presentPeriods").asInt()).isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-22 組織スコープ規程
    // ═════════════════════════════════════════════════════════════════════

    /** 組織スコープ規程の評価・解消を試みる者。 */
    enum OrgActor {
        ORG_ADMIN_A(true), ORG_MEMBER_A(false), HOMEROOM(false), TEAM_ADMIN(false),
        OTHER_TENANT_ADMIN(false), SYSTEM_ADMIN(false);

        final boolean allowed;

        OrgActor(boolean allowed) {
            this.allowed = allowed;
        }
    }

    @ParameterizedTest(name = "AC-22 組織スコープ規程の解消・評価 × {0}")
    @EnumSource(OrgActor.class)
    @DisplayName("AC-22: 組織スコープ規程の評価・解消は組織の ADMIN のみ。それ以外は bare-id の 404")
    void 組織スコープ規程の評価と解消は組織ADMINのみ(OrgActor who) throws Exception {
        Long orgAdminA = newUser("org-admin-a");
        MembershipTestHelper.insertMembership(em, orgAdminA, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminA, "ADMIN", null, orgAId);
        Long orgMemberA = newUser("org-member-a");
        MembershipTestHelper.insertMembership(em, orgMemberA, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        Long orgStudent = newUser("org-student");
        MembershipTestHelper.insertMembership(em, orgStudent, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        Long orgRule = insertRule(null, orgAId);
        Long summary = insertSummary(teamAId, orgStudent);
        Long orgEvaluation = insertEvaluation(orgRule, orgStudent, summary);
        em.flush();
        em.clear();

        Long userId = switch (who) {
            case ORG_ADMIN_A -> orgAdminA;
            case ORG_MEMBER_A -> orgMemberA;
            case HOMEROOM -> actors.get(Actor.HOMEROOM);
            case TEAM_ADMIN -> actors.get(Actor.ADMIN);
            case OTHER_TENANT_ADMIN -> actors.get(Actor.OTHER_TENANT_ADMIN);
            case SYSTEM_ADMIN -> actors.get(Actor.SYSTEM_ADMIN);
        };
        auth(userId);

        MvcResult resolve = mockMvc.perform(post("/api/v1/attendance/requirements/evaluations/{e}/resolve",
                        orgEvaluation).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("resolutionNote", "SAZ組織規程の解消")))).andReturn();

        assertThat(resolve.getResponse().getStatus()).as("組織規程の解消（%s）", who)
                .isEqualTo(who.allowed ? 200 : 404);
        if (!who.allowed) {
            MvcResult evaluate = mockMvc.perform(post(
                    "/api/v1/students/{s}/attendance/requirements/{r}/evaluate", orgStudent, orgRule)).andReturn();
            assertThat(evaluate.getResponse().getStatus()).as("組織規程の評価（%s）", who).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("AC-22: SYSTEM_ADMIN と組織 DEPUTY_ADMIN の兼任者は、組織スコープ規程の評価・解消ができる（スコープ付き資格で許可）")
    void 組織スコープ規程はSYSTEM_ADMINと組織DEPUTYの兼任者を許可する() throws Exception {
        Long dual = newUser("sysadmin-org-deputy");
        MembershipTestHelper.insertMembership(em, dual, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, dual, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertUserRole(em, dual, "DEPUTY_ADMIN", null, orgAId);
        Long orgStudent = newUser("org-student-dual");
        MembershipTestHelper.insertMembership(em, orgStudent, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        Long orgRule = insertRule(null, orgAId);
        Long summary = insertSummary(teamAId, orgStudent);
        Long orgEvaluation = insertEvaluation(orgRule, orgStudent, summary);
        em.flush();
        em.clear();
        auth(dual);

        MvcResult resolve = mockMvc.perform(post("/api/v1/attendance/requirements/evaluations/{e}/resolve",
                        orgEvaluation).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("resolutionNote", "SAZ兼任者の解消")))).andReturn();

        assertThat(resolve.getResponse().getStatus()).isEqualTo(200);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-15 本人経路の非回帰・担任の主経路
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-15 /me 経路の非回帰 × {0}")
    @EnumSource(value = Actor.class, names = {"STUDENT_SELF", "CLASSMATE"})
    @DisplayName("AC-15: 生徒本人は /me/attendance/daily|timeline|statistics/term を 200 で取得でき、他生徒のデータは混ざらない")
    void 本人経路は非回帰(Actor actor) throws Exception {
        Long self = actors.get(actor);
        auth(actor);

        MvcResult daily = mockMvc.perform(get("/api/v1/me/attendance/daily")
                .param("from", date.minusDays(1).toString()).param("to", date.plusDays(1).toString())).andReturn();
        assertThat(daily.getResponse().getStatus()).isEqualTo(200);
        assertThat(longs(data(daily), "studentUserId")).as("自分の記録だけ").containsOnly(self).hasSize(1);

        MvcResult timeline = mockMvc.perform(get("/api/v1/me/attendance/timeline")
                .param("date", date.toString())).andReturn();
        assertThat(timeline.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(timeline).path("studentUserId").asLong()).isEqualTo(self);

        MvcResult term = mockMvc.perform(get("/api/v1/me/attendance/statistics/term")
                .param("teamId", String.valueOf(teamAId))
                .param("from", date.minusDays(1).toString()).param("to", date.plusDays(1).toString())).andReturn();
        assertThat(term.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(term).path("studentUserId").asLong()).isEqualTo(self);
    }

    @Test
    @DisplayName("AC-15: 担任の主経路（点呼登録→一覧→修正→CSV）が認可是正後も完走する")
    void 担任の主経路は完走する() throws Exception {
        var day = date.plusDays(1);
        auth(Actor.HOMEROOM);

        MvcResult rollCall = mockMvc.perform(post("/api/v1/teams/{t}/attendance/daily/roll-call", teamAId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(entriesBody(day, entry(studentAId, "ATTENDING"), entry(classmateId, "ABSENT")))))
                .andReturn();
        assertThat(rollCall.getResponse().getStatus()).isEqualTo(201);

        MvcResult list = mockMvc.perform(get("/api/v1/teams/{t}/attendance/daily", teamAId)
                .param("date", day.toString())).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(list).path("totalCount").asInt()).isEqualTo(2);
        Long recordId = data(list).path("records").get(0).path("id").asLong();

        MvcResult fix = mockMvc.perform(patch("/api/v1/teams/{t}/attendance/daily/{r}", teamAId, recordId)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("comment", "SAZ修正")))).andReturn();
        assertThat(fix.getResponse().getStatus()).isEqualTo(200);

        MvcResult csv = mockMvc.perform(get("/api/v1/teams/{t}/attendance/export", teamAId)
                .param("from", day.toString()).param("to", day.toString())).andReturn();
        assertThat(csv.getResponse().getStatus()).isEqualTo(200);
    }
}
