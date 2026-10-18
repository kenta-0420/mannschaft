package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261017-1726: 正規設定APIから代理指定までの永続化契約を実Security・実MySQLで検証する。
 * 設定値をEntityへ直接投入せず、POST/PATCH後にflush/clearして再読込する。
 * 既存認可・既存委任維持の条件は初回greenもあり得る現契約の固定である。
 * 通知配送は観測しない。commit後の実失敗rollbackは既存activity同期ITが担う。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScheduleProxySettingsContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ScheduleRepository schedules;
    @Autowired private ScheduleDelegationRepository delegations;
    @Autowired private ScheduleAttendanceRepository attendances;
    @Autowired private ScheduleAttendanceService attendanceService;
    @Autowired private ScheduleMapper mapper;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @PersistenceContext private EntityManager em;
    private Long teamId;
    private Long orgId;
    private Long admin;
    private Long deputy;
    private Long member;
    private Long delegate;
    private Long outsider;
    private Long foreignAdmin;
    private Long foreignTeam;
    private OffsetDateTime start;

    @BeforeEach
    void 準備() {
        String nonce = UUID.randomUUID().toString().substring(0, 12);
        teamId = ScheduleCommentTestFixtures.insertTeam(em, "代理設定試練", "proxy-team-" + nonce);
        orgId = ScheduleCommentTestFixtures.insertOrganization(em, "代理設定組織", "proxy-org-" + nonce);
        foreignTeam = ScheduleCommentTestFixtures.insertTeam(em, "他scope", "proxy-other-" + nonce);
        admin = actor("admin", nonce);
        deputy = actor("deputy", nonce);
        member = actor("member", nonce);
        delegate = actor("delegate", nonce);
        outsider = actor("outsider", nonce);
        foreignAdmin = actor("foreign", nonce);
        for (ScopeType type : List.of(ScopeType.TEAM, ScopeType.ORGANIZATION)) {
            Long scopeId = type == ScopeType.TEAM ? teamId : orgId;
            for (Long actor : List.of(admin, deputy, member, delegate)) {
                MembershipTestHelper.insertMembership(em, actor, type, scopeId, RoleKind.MEMBER);
            }
            MembershipTestHelper.insertUserRole(em, admin, "ADMIN",
                    type == ScopeType.TEAM ? scopeId : null, type == ScopeType.ORGANIZATION ? scopeId : null);
            MembershipTestHelper.insertUserRole(em, deputy, "DEPUTY_ADMIN",
                    type == ScopeType.TEAM ? scopeId : null, type == ScopeType.ORGANIZATION ? scopeId : null);
        }
        MembershipTestHelper.insertMembership(em, foreignAdmin, ScopeType.TEAM, foreignTeam, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, foreignAdmin, "ADMIN", foreignTeam, null);
        start = LocalDateTime.now().plusDays(20).withHour(10).withMinute(0).withSecond(0).withNano(0)
                .atZone(ZoneId.of("Asia/Tokyo")).toOffsetDateTime();
        em.flush();
        em.clear();
    }

    @ParameterizedTest
    @CsvSource({"team,false,false", "team,false,true", "team,true,false", "team,true,true",
            "organization,false,false", "organization,false,true", "organization,true,false", "organization,true,true"})
    void AC1_作成した全組合せが応答と再読込で一致する(String scope, boolean allow, boolean auto) throws Exception {
        long id = create(scope, flags(allow, auto));
        assertStored(id, allow, auto);
        assertGet(scope, id, allow, auto);
        JsonNode list = data(mvc.perform(get(base(scope)).with(user(admin.toString()))
                .param("from", start.minusDays(1).toString()).param("to", start.plusDays(2).toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertFlags(list.get(0), allow, auto);
    }

    @ParameterizedTest
    @ValueSource(strings = {"team", "organization"})
    void AC2_省略とnullの作成はfalseになる(String scope) throws Exception {
        assertStored(create(scope, Map.of()), false, false);
        Map<String, Object> values = new HashMap<>();
        values.put("allowProxyAttendance", null);
        values.put("isProxyAutoAccept", null);
        long id = create(scope, values);
        assertStored(id, false, false);
        assertGet(scope, id, false, false);
    }

    @Test
    void AC2_PERSONALの正規作成更新では代理設定を有効にしない() throws Exception {
        Map<String, Object> body = new HashMap<>(flags(true, true));
        body.put("title", "個人の既存契約");
        body.put("startAt", start.toString());
        body.put("endAt", start.plusHours(1).toString());
        body.put("allDay", false);
        JsonNode created = data(mvc.perform(post("/api/v1/me/schedules").with(user(admin.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = created.path("id").asLong();
        assertStored(id, false, false);
        assertThat(created.has("allowProxyAttendance")).isFalse();
        mvc.perform(patch("/api/v1/me/schedules/{id}", id).with(user(admin.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(flags(true, true))))
                .andExpect(status().isOk());
        assertStored(id, false, false);
        // 共有PATCHの既存「実scope・所有者」認可経由でも個人予定へ設定を広げない。
        update("team", id, flags(true, true), "THIS_ONLY");
        assertStored(id, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"team", "organization"})
    void AC3_snakeCaseも作成とPATCHで受理する(String scope) throws Exception {
        long id = create(scope, Map.of("allow_proxy_attendance", true, "is_proxy_auto_accept", true));
        assertStored(id, true, true);
        update(scope, id, Map.of("allow_proxy_attendance", false, "is_proxy_auto_accept", false), "THIS_ONLY");
        assertStored(id, false, false);
        assertGet(scope, id, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"team", "organization"})
    void AC4と5_省略とnullを保持し明示falseだけ反映する(String scope) throws Exception {
        long id = create(scope, flags(true, true));
        update(scope, id, Map.of("title", "設定を保持する編集"), "THIS_ONLY");
        assertStored(id, true, true);
        Map<String, Object> values = new HashMap<>();
        values.put("allowProxyAttendance", null);
        values.put("isProxyAutoAccept", null);
        update(scope, id, values, "THIS_ONLY");
        assertStored(id, true, true);
        update(scope, id, Map.of("isProxyAutoAccept", false), "THIS_ONLY");
        assertStored(id, true, false);
        update(scope, id, Map.of("allowProxyAttendance", false), "THIS_ONLY");
        assertStored(id, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"THIS_ONLY", "THIS_AND_FOLLOWING", "ALL"})
    void AC7_繰返しの日時移動でも対象回へ設定を渡す(String updateScope) throws Exception {
        Map<String, Object> values = new HashMap<>(flags(true, true));
        values.put("recurrenceRule", Map.of("type", "DAILY", "interval", 1, "endType", "COUNT", "count", 3));
        long parentId = create("team", values);
        List<ScheduleEntity> children = schedules.findByParentScheduleIdOrderByStartAtAsc(parentId);
        assertThat(children).hasSize(3);
        for (ScheduleEntity child : children) assertStored(child.getId(), true, true);
        ScheduleEntity selected = "ALL".equals(updateScope) ? schedules.findById(parentId).orElseThrow() : children.get(1);
        Map<String, Object> patch = new HashMap<>(flags(false, false));
        patch.put("startAt", selected.getStartAt().plusHours(1).atZone(ZoneId.of("Asia/Tokyo")).toOffsetDateTime().toString());
        patch.put("endAt", selected.getEndAt().plusHours(1).atZone(ZoneId.of("Asia/Tokyo")).toOffsetDateTime().toString());
        update("team", selected.getId(), patch, updateScope);
        assertStored(parentId, !"ALL".equals(updateScope), !"ALL".equals(updateScope));
        for (int i = 0; i < children.size(); i++) {
            boolean changed = "ALL".equals(updateScope) || i == 1
                    || "THIS_AND_FOLLOWING".equals(updateScope) && i > 1;
            assertStored(children.get(i).getId(), !changed, !changed);
        }
    }

    @Test
    void AC7_withTimesは部分更新flagを失わない() throws Exception {
        UpdateScheduleRequest request = json.readValue("{\"allowProxyAttendance\":false,\"isProxyAutoAccept\":true}", UpdateScheduleRequest.class);
        JsonNode shifted = json.valueToTree(request.withTimes(start, start.plusHours(2)));
        assertFlags(shifted, false, true);
    }

    @Test
    void AC6_Mapperとクロス招待受諾の応答も保存値を返す() throws Exception {
        long id = create("team", flags(true, true));
        assertStored(id, true, true);
        ScheduleEntity source = schedules.findById(id).orElseThrow();
        assertFlags(json.valueToTree(mapper.toResponse(source)), true, true);
        assertFlags(json.valueToTree(mapper.toDetailResponse(source)), true, true);
        JsonNode invitation = data(mvc.perform(post(base("team") + "/{id}/cross-invite", id)
                .with(user(admin.toString())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("targetType", "ORGANIZATION", "targetId", orgId))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode copy = data(mvc.perform(post("/api/v1/organizations/{org}/schedule-invitations/{invitation}/accept",
                orgId, invitation.path("id").asLong()).with(user(admin.toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFlags(copy, true, true);
        assertStored(copy.path("id").asLong(), true, true);
    }

    @Test
    void AC13_一覧の設定取得で予定件数比例のqueryを増やさない() throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean original = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            create("team", flags(true, true));
            long single = listQueryCount(statistics);
            for (int i = 0; i < 8; i++) create("team", flags(i % 2 == 0, false));
            long many = listQueryCount(statistics);
            System.out.printf("proxy-settings-query-count single=%d many=%d%n", single, many);
            assertThat(many).isLessThanOrEqualTo(single + 2);
        } finally {
            statistics.setStatisticsEnabled(original);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC9_正規設定を保存すると新規委任の初期状態へ反映する(boolean auto) throws Exception {
        long id = create("team", flags(true, auto));
        JsonNode result = createDelegation(id, member, delegate);
        assertThat(result.path("status").asText()).isEqualTo(auto ? "ACCEPTED" : "PENDING");
    }

    @Test
    void AC8_OFF後も既存の承認待ちと受諾済みを維持して新規だけ拒否する() throws Exception {
        long id = create("team", flags(true, false));
        attendanceService.generateAttendanceRecords(id, List.of(admin, deputy, member, delegate));
        UUID pending = UUID.fromString(createDelegation(id, member, delegate).path("id").asText());
        update("team", id, Map.of("isProxyAutoAccept", true), "THIS_ONLY");
        UUID accepted = UUID.fromString(createDelegation(id, admin, deputy).path("id").asText());
        em.flush();
        em.clear();
        // 委任作成に伴う正当な出欠変更の後、OFF 操作だけの影響を比較する。
        JsonNode attendanceBefore = json.valueToTree(attendances.findByScheduleIdOrderByUserIdAsc(id));
        assertThat(attendanceBefore).hasSize(4);
        update("team", id, flags(false, false), "THIS_ONLY");
        em.flush();
        em.clear();
        assertThat(delegations.findById(pending).orElseThrow().getStatus()).isEqualTo(ScheduleDelegationStatus.PENDING);
        assertThat(delegations.findById(accepted).orElseThrow().getStatus()).isEqualTo(ScheduleDelegationStatus.ACCEPTED);
        assertThat(json.<JsonNode>valueToTree(attendances.findByScheduleIdOrderByUserIdAsc(id)))
                .isEqualTo(attendanceBefore);
        mvc.perform(post("/api/v1/schedules/{id}/delegations", id).with(user(member.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("delegateId", deputy))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("SCHEDULE_076"));
        mvc.perform(patch("/api/v1/schedule-delegations/{id}/accept", pending).with(user(delegate.toString())))
                .andExpect(status().isOk());
        em.flush();
        em.clear();
        assertThat(delegations.findById(pending).orElseThrow().getStatus()).isEqualTo(ScheduleDelegationStatus.ACCEPTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"team", "organization"})
    void AC10_既存管理者と副管理者を許可し非権限と別scopeを拒否する(String scope) throws Exception {
        long id = create(scope, flags(true, true));
        mvc.perform(patch(base(scope) + "/{id}", id).with(user(deputy.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(flags(true, false))))
                .andExpect(status().isOk());
        for (Long actor : List.of(member, outsider, foreignAdmin)) {
            mvc.perform(patch(base(scope) + "/{id}", id).with(user(actor.toString()))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(flags(false, true))))
                    .andExpect(status().isForbidden());
            assertStored(id, true, false);
        }
        mvc.perform(patch(base(scope) + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"allowProxyAttendance\":false}"))
                .andExpect(status().isUnauthorized());
        assertStored(id, true, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"invalid\"", "{}", "[]"})
    void AC14a_不正booleanは400で現在値を変えない(String invalid) throws Exception {
        long id = create("team", flags(true, true));
        mvc.perform(patch(base("team") + "/{id}", id).with(user(admin.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowProxyAttendance\":" + invalid + "}"))
                .andExpect(status().isBadRequest());
        assertStored(id, true, true);
    }

    private Long actor(String label, String nonce) {
        return ScheduleCommentTestFixtures.insertUser(em, "proxy-" + label + "-" + nonce + "@example.com", "代理設定" + label);
    }

    private long listQueryCount(org.hibernate.stat.Statistics statistics) throws Exception {
        em.flush();
        em.clear();
        long before = statistics.getPrepareStatementCount();
        mvc.perform(get(base("team")).with(user(admin.toString()))
                .param("from", start.minusDays(1).toString()).param("to", start.plusDays(2).toString()))
                .andExpect(status().isOk());
        return statistics.getPrepareStatementCount() - before;
    }

    private String base(String scope) {
        return "/api/v1/" + ("team".equals(scope) ? "teams/" + teamId : "organizations/" + orgId) + "/schedules";
    }

    private Map<String, Object> flags(boolean allow, boolean auto) {
        return Map.of("allowProxyAttendance", allow, "isProxyAutoAccept", auto);
    }

    private long create(String scope, Map<String, Object> values) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "代理設定予定", "startAt", start.toString(),
                "endAt", start.plusHours(2).toString(), "allDay", false, "eventType", "OTHER", "attendanceRequired", true));
        body.putAll(values);
        JsonNode result = data(mvc.perform(post(base(scope)).with(user(admin.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertFlags(result, Boolean.TRUE.equals(body.getOrDefault("allowProxyAttendance", body.get("allow_proxy_attendance"))),
                Boolean.TRUE.equals(body.getOrDefault("isProxyAutoAccept", body.get("is_proxy_auto_accept"))));
        return result.path("id").asLong();
    }

    private void update(String scope, long id, Map<String, Object> body, String updateScope) throws Exception {
        JsonNode result = data(mvc.perform(patch(base(scope) + "/{id}", id).with(user(admin.toString()))
                .param("updateScope", updateScope).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        em.flush();
        em.clear();
        ScheduleEntity stored = schedules.findById(id).orElseThrow();
        assertFlags(result, stored.getAllowProxyAttendance(), stored.getIsProxyAutoAccept());
    }

    private void assertGet(String scope, long id, boolean allow, boolean auto) throws Exception {
        assertFlags(data(mvc.perform(get(base(scope) + "/{id}", id).with(user(admin.toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()), allow, auto);
    }

    private void assertStored(long id, boolean allow, boolean auto) {
        em.flush();
        em.clear();
        ScheduleEntity loaded = schedules.findById(id).orElseThrow();
        assertThat(loaded.getAllowProxyAttendance()).isEqualTo(allow);
        assertThat(loaded.getIsProxyAutoAccept()).isEqualTo(auto);
    }

    private void assertFlags(JsonNode node, boolean allow, boolean auto) {
        assertThat(node.path("allowProxyAttendance").isBoolean()).isTrue();
        assertThat(node.path("isProxyAutoAccept").isBoolean()).isTrue();
        assertThat(node.path("allowProxyAttendance").booleanValue()).isEqualTo(allow);
        assertThat(node.path("isProxyAutoAccept").booleanValue()).isEqualTo(auto);
    }

    private JsonNode createDelegation(long id, Long actor, Long target) throws Exception {
        return data(mvc.perform(post("/api/v1/schedules/{id}/delegations", id).with(user(actor.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("delegateId", target))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode data(String response) throws Exception {
        return json.readTree(response).path("data");
    }
}
