package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261008-1253: シフト枠の版番号を GET と更新応答から連続して利用する契約。
 *
 * <p>共通 MOCK 構成、実 JWT フィルター、実 MySQL を使う。新 getter に依存せず
 * 公開 JSON と永続化された counter・割当履歴を観測し、欠落応答と Integer 入力の
 * 範囲不足を実装前に赤くする。テスト TX は fixture を各ケース後に巻き戻すためだけに使い、
 * DB 観測前に flush/clear して managed entity の自己整合だけで緑にしない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261008-1253 ShiftSlotController 枠版番号の実 API 契約")
class ShiftSlotVersionContractIT extends AbstractMySqlIntegrationTest {
    private static final long ABOVE_INTEGER_MAX = 2_147_483_648L;
    private static final long MISSING_SLOT_ID = 999_999_999L;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private FeatureFlagRepository flags;
    @Autowired private CacheManager caches;
    @PersistenceContext private EntityManager em;

    private Long admin;
    private Long member;
    private Long outsider;
    private Long foreignAdmin;
    private Long scheduleId;
    private Long slotId;

    @BeforeEach
    void fixtureを実Repositoryで保存する() {
        FeatureFlagTestSupport.enable(flags, caches, "FEATURE_SHIFT_ENABLED");
        admin = user("admin");
        member = user("member");
        outsider = user("outsider");
        foreignAdmin = user("foreign");
        Long teamId = team("own");
        Long foreignTeamId = team("foreign");
        MembershipTestHelper.insertMembership(em, admin, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, member, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, foreignAdmin, ScopeType.TEAM, foreignTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, foreignAdmin, "ADMIN", foreignTeamId, null);
        scheduleId = schedules.save(ShiftScheduleEntity.builder()
                .teamId(teamId).title("版番号契約")
                .periodType(ShiftPeriodType.WEEKLY).status(ShiftScheduleStatus.DRAFT)
                .startDate(LocalDate.of(2026, 10, 8)).endDate(LocalDate.of(2026, 10, 10))
                .createdBy(admin).build()).getId();
        slotId = slots.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId).slotDate(LocalDate.of(2026, 10, 9))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0))
                .requiredCount(2).build()).getId();
        em.flush();
        em.clear();
    }

    @Test
    void 初回ゼロを取得して追加解除を更新応答の版で連続実行できる() throws Exception {
        long initial = listVersion();
        assertThat(initial).isZero();
        JsonNode added = assignment(Map.of("addUserIds", List.of(member), "slotVersion", initial));
        assertThat(added.path("version").longValue()).isEqualTo(1L);
        assertThat(added.path("assignedUserIds").get(0).longValue()).isEqualTo(member);
        assertThat(added.path("warnings").isArray()).isTrue();
        assertThat(databaseVersion()).isEqualTo(1L);
        JsonNode removed = assignment(Map.of("removeUserIds", List.of(member),
                "slotVersion", added.path("version").longValue()));
        assertThat(removed.path("version").longValue()).isEqualTo(2L);
        assertThat(removed.path("assignedUserIds").isEmpty()).isTrue();
        assertThat(databaseVersion()).isEqualTo(2L);
        assertThat(listVersion()).isEqualTo(2L);
    }

    @Test
    void noteだけの更新もflush後の実counterを返す() throws Exception {
        JsonNode updated = body(http(admin, "PATCH", "/api/v1/shifts/slots/" + slotId,
                Map.of("note", "版番号を確定する")).andExpect(status().isOk()));
        assertThat(updated.path("version").isIntegralNumber()).isTrue();
        assertThat(updated.path("version").longValue()).isEqualTo(1L);
        assertThat(databaseVersion()).isEqualTo(updated.path("version").longValue());
        assignment(Map.of("addUserIds", List.of(member), "slotVersion", updated.path("version").longValue()));
    }

    @Test
    void stale版は409で枠と履歴を変更しない() throws Exception {
        assignment(Map.of("addUserIds", List.of(member), "slotVersion", 0));
        String before = databaseSnapshot();
        http(admin, "PATCH", assignmentPath(), Map.of("removeUserIds", List.of(member), "slotVersion", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SHIFT_018"));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void Integer上限を越える実counterでもLong契約で更新できる() throws Exception {
        em.createNativeQuery("UPDATE shift_slots SET version = :version WHERE id = :id")
                .setParameter("version", ABOVE_INTEGER_MAX).setParameter("id", slotId).executeUpdate();
        em.clear();
        assertThat(databaseVersion()).isEqualTo(ABOVE_INTEGER_MAX);
        JsonNode added = assignment(Map.of("addUserIds", List.of(member), "slotVersion", ABOVE_INTEGER_MAX));
        assertThat(added.path("version").longValue()).isEqualTo(ABOVE_INTEGER_MAX + 1);
        assertThat(databaseVersion()).isEqualTo(ABOVE_INTEGER_MAX + 1);
        assertThat(listVersion()).isEqualTo(ABOVE_INTEGER_MAX + 1);
    }

    @Test
    void 必須版欠落は400で書き込まない() throws Exception {
        String before = databaseSnapshot();
        http(admin, "PATCH", assignmentPath(), Map.of("addUserIds", List.of(member)))
                .andExpect(status().isBadRequest());
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void 非所属と他チーム管理者は実在と不在とも同じ404で変更しない() throws Exception {
        String before = databaseSnapshot();
        for (Long actor : List.of(outsider, foreignAdmin)) {
            String existing = http(actor, "PATCH", assignmentPath(), Map.of("slotVersion", 0))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            String missing = http(actor, "PATCH", "/api/v1/shifts/slots/" + MISSING_SLOT_ID + "/assignments",
                    Map.of("slotVersion", 0)).andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(existing)).isEqualTo(mapper.readTree(missing));
        }
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void 匿名の実フィルターは401で変更しない() throws Exception {
        String before = databaseSnapshot();
        http(null, "PATCH", assignmentPath(), Map.of("slotVersion", 0)).andExpect(status().isUnauthorized());
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    private long listVersion() throws Exception {
        JsonNode data = body(http(admin, "GET", "/api/v1/shifts/schedules/" + scheduleId + "/slots", null)
                .andExpect(status().isOk()));
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("version").isIntegralNumber()).isTrue();
        return data.get(0).path("version").longValue();
    }

    private JsonNode assignment(Object payload) throws Exception {
        return body(http(admin, "PATCH", assignmentPath(), payload).andExpect(status().isOk()));
    }

    private String assignmentPath() { return "/api/v1/shifts/slots/" + slotId + "/assignments"; }

    private JsonNode body(ResultActions response) throws Exception {
        return mapper.readTree(response.andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions http(Long actor, String method, String path, Object payload) throws Exception {
        var request = request(HttpMethod.valueOf(method), path);
        if (actor != null) request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER")));
        if (payload != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payload));
        return mvc.perform(request);
    }

    private long databaseVersion() {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery("SELECT version FROM shift_slots WHERE id = :id")
                .setParameter("id", slotId).getSingleResult()).longValue();
    }

    private String databaseSnapshot() {
        em.flush();
        em.clear();
        return em.createNativeQuery("SELECT CONCAT(version, ':', COALESCE(assigned_user_ids, 'null')) FROM shift_slots WHERE id = :id")
                .setParameter("id", slotId).getSingleResult() + "|"
                + java.util.Arrays.deepToString(em.createNativeQuery("SELECT * FROM shift_assignments WHERE slot_id = :id ORDER BY id")
                .setParameter("id", slotId).getResultList().toArray());
    }

    private Long user(String label) {
        return users.save(UserEntity.builder().email("slot-version-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }

    private Long team(String label) {
        return teams.save(TeamEntity.builder().slug("slot-version-" + UUID.randomUUID())
                .name(label).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
    }
}
