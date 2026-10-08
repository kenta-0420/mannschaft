package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
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
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261008-1407: 変更依頼の公開 JSON に実版を返し、審査へ同じ Long 版を渡す契約。
 * 共通 MOCK・実 JWT・実 MySQL を使い、fixture と各 HTTP と DB 観測を独立 TX にする。
 * Integer DTO のままコンパイルできる実 JSON を送り、setup 失敗を semantic red としない。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261008-1407 ShiftChangeRequestController 審査版番号の実 API 契約")
class ShiftChangeRequestVersionContractIT extends AbstractMySqlIntegrationTest {
    private static final long ABOVE_INTEGER_MAX = 2_147_483_648L;
    private static final long MISSING_REQUEST_ID = 999_999_999L;
    private static final String SHIFT_FLAG = "FEATURE_SHIFT_ENABLED";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftChangeRequestRepository changeRequests;
    @Autowired private FeatureFlagRepository flags;
    @Autowired private CacheManager caches;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager em;

    private Long admin;
    private Long member;
    private Long outsider;
    private Long foreignAdmin;
    private Long scheduleId;
    private Long slotId;
    private Long requestId;
    private Long teamId;
    private Long foreignTeamId;
    private FeatureFlagEntity flagBefore;
    private Long ownedFlagId;

    @BeforeEach
    void fixtureを実Repositoryで保存する() {
        inTx(() -> {
            flagBefore = flags.findByFlagKey(SHIFT_FLAG).map(flag -> flag.toBuilder().build()).orElse(null);
            FeatureFlagTestSupport.enable(flags, caches, SHIFT_FLAG);
            flags.flush();
            ownedFlagId = flags.findByFlagKey(SHIFT_FLAG).orElseThrow().getId();
            admin = user("admin");
            member = user("member");
            outsider = user("outsider");
            foreignAdmin = user("foreign");
            teamId = team("own");
            foreignTeamId = team("foreign");
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
            requestId = changeRequests.save(ShiftChangeRequestEntity.builder()
                    .scheduleId(scheduleId).slotId(slotId).requestedBy(member)
                    .requestType(ChangeRequestType.PRE_CONFIRM_EDIT).reason("実版契約の所有fixture")
                    .build()).getId();
            em.flush();
            em.clear();
        return null;
        });
    }

    @AfterEach
    void 所有fixtureだけを独立TXで削除する() {
        if (slotId == null) return;
        try {
        inTx(() -> {
            changeRequests.deleteById(requestId);
            changeRequests.flush();
            em.createNativeQuery("DELETE FROM shift_assignments WHERE slot_id = :id")
                    .setParameter("id", slotId).executeUpdate();
            slots.deleteById(slotId);
            slots.flush();
            schedules.deleteById(scheduleId);
            schedules.flush();
            List<Long> actors = List.of(admin, member, outsider, foreignAdmin);
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)")
                    .setParameter("ids", actors).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)")
                    .setParameter("ids", actors).executeUpdate();
            teams.deleteAllById(List.of(teamId, foreignTeamId));
            users.deleteAllById(actors);
            return null;
        });
        } finally {
            inTx(() -> {
                FeatureFlagEntity current = flags.findByFlagKey(SHIFT_FLAG).orElseThrow();
                assertThat(current.getId()).isEqualTo(ownedFlagId);
                if (flagBefore == null) {
                    flags.delete(current);
                } else {
                    // JPA の監査フックで updated_at を再更新せず、変更した列を元値へ戻す。
                    em.createNativeQuery("UPDATE feature_flags SET is_enabled = :enabled, updated_by = :actor, updated_at = :time WHERE id = :id AND flag_key = :key")
                            .setParameter("enabled", flagBefore.getIsEnabled())
                            .setParameter("actor", flagBefore.getUpdatedBy())
                            .setParameter("time", flagBefore.getUpdatedAt())
                            .setParameter("id", ownedFlagId).setParameter("key", SHIFT_FLAG).executeUpdate();
                }
                return null;
            });
            FeatureFlagTestSupport.clearFlagCaches(caches);
        }
    }

    @Test
    void GETと一覧は初回ゼロの実版と既存ネスト値を返す() throws Exception {
        JsonNode detail = body(http(admin, "GET", requestPath(), null).andExpect(status().isOk()));
        assertThat(detail.path("version").isIntegralNumber()).isTrue();
        assertThat(detail.path("version").longValue()).isZero();
        assertThat(detail.path("requestInfo").path("requestedBy").longValue()).isEqualTo(member);
        assertThat(detail.path("reviewInfo").path("status").asText()).isEqualTo("OPEN");
        assertThat(detail.path("timing").path("createdAt").isTextual()).isTrue();
        JsonNode list = body(http(admin, "GET", "/api/v1/shifts/change-requests?scheduleId=" + scheduleId, null)
                .andExpect(status().isOk()));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("version").isIntegralNumber()).isTrue();
        assertThat(list.get(0).path("version").longValue()).isZero();
    }

    @Test
    void 初回ゼロ審査の応答はreadback前に確定版を返す() throws Exception {
        assertThat(databaseVersion()).isZero();
        JsonNode reviewed = body(http(admin, "PATCH", requestPath() + "/review", review(0L))
                .andExpect(status().isOk()));
        // DB の readback/flush が応答の旧版を救済する前に公開 JSON を検証する。
        assertThat(reviewed.path("version").isIntegralNumber()).isTrue();
        assertThat(reviewed.path("version").longValue()).isEqualTo(1L);
        assertThat(reviewed.path("reviewInfo").path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(reviewed.path("reviewInfo").path("reviewerId").longValue()).isEqualTo(admin);
        assertThat(databaseVersion()).isEqualTo(1L);
    }

    @Test
    void Integer上限を越える実OPEN版をJSONで審査できる() throws Exception {
        inTx(() -> {
            assertThat(em.createNativeQuery("UPDATE shift_change_requests SET version = :version WHERE id = :id")
                    .setParameter("version", ABOVE_INTEGER_MAX).setParameter("id", requestId).executeUpdate()).isEqualTo(1);
            return null;
        });
        assertThat(databaseVersion()).isEqualTo(ABOVE_INTEGER_MAX);
        JsonNode reviewed = body(http(admin, "PATCH", requestPath() + "/review", review(ABOVE_INTEGER_MAX))
                .andExpect(status().isOk()));
        assertThat(reviewed.path("version").isIntegralNumber()).isTrue();
        assertThat(reviewed.path("version").longValue()).isEqualTo(ABOVE_INTEGER_MAX + 1);
        assertThat(databaseVersion()).isEqualTo(ABOVE_INTEGER_MAX + 1);
    }

    @Test
    void stale版409は依頼の全列と関連割当履歴を変更しない() throws Exception {
        String before = databaseSnapshot();
        http(admin, "PATCH", requestPath() + "/review", review(1L))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SHIFT_018"));
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void 版欠落400は書き込まない() throws Exception {
        String before = databaseSnapshot();
        http(admin, "PATCH", requestPath() + "/review", Map.of("decision", "ACCEPTED"))
                .andExpect(status().isBadRequest());
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void 匿名は実フィルターで401となり書き込まない() throws Exception {
        String before = databaseSnapshot();
        http(null, "GET", requestPath(), null).andExpect(status().isUnauthorized());
        http(null, "PATCH", requestPath() + "/review", review(0L)).andExpect(status().isUnauthorized());
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    void 非所属と他チーム管理者は実在と不在とも404で書き込まない() throws Exception {
        String before = databaseSnapshot();
        for (Long actor : List.of(outsider, foreignAdmin)) {
            String existing = http(actor, "PATCH", requestPath() + "/review", review(0L))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            String missing = http(actor, "PATCH", "/api/v1/shifts/change-requests/" + MISSING_REQUEST_ID + "/review", review(0L))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(existing).path("error")).isEqualTo(mapper.readTree(missing).path("error"));
        }
        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    private Map<String, Object> review(long version) { return Map.of("decision", "ACCEPTED", "version", version); }
    private String requestPath() { return "/api/v1/shifts/change-requests/" + requestId; }
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
        return inTx(() -> ((Number) em.createNativeQuery("SELECT version FROM shift_change_requests WHERE id = :id")
                .setParameter("id", requestId).getSingleResult()).longValue());
    }
    private String databaseSnapshot() {
        return inTx(() -> java.util.Arrays.deepToString(em.createNativeQuery("SELECT * FROM shift_change_requests WHERE id = :id")
                .setParameter("id", requestId).getResultList().toArray()) + "|"
                + java.util.Arrays.deepToString(em.createNativeQuery("SELECT * FROM shift_assignments WHERE slot_id = :id ORDER BY id")
                .setParameter("id", slotId).getResultList().toArray()));
    }
    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long user(String label) {
        return users.save(UserEntity.builder().email("slot-version-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }

    private Long team(String label) {
        return teams.save(TeamEntity.builder().slug("sv-" + new java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString(36))
                .name(label).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
    }
}
