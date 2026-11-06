package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261004-1819: 親チーム不存在のシフト作成拒否を実JWT・実フィルタ・実MySQLで固定する。
 *
 * <p>AC-01/02は是正前に201となるred先行試験。他の認可・形式・日付・feature gateは
 * 初回greenを許す既存契約の回帰固定である。製品Beanはモックせず、共通基底の外部Redisだけを継承する。
 * 通知配送を検証しない単一作成試験のためテストTX内でflush/clearし、作成行を実DBで観測してrollbackする。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261004-1819 シフト作成の親チーム存在契約")
class ShiftScheduleCreateTeamExistenceContractIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/shifts/schedules";
    private static final String FLAG = "FEATURE_SHIFT_ENABLED";
    private static final long UNISSUED_TEAM_ID = 900_000_000_000L;
    private static final String BODY = "{\"title\":\"親存在契約\",\"periodType\":\"WEEKLY\","
            + "\"startDate\":\"2030-04-01\",\"endDate\":\"2030-04-07\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private FeatureFlagRepository flags;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager em;

    private final Map<String, Long> actors = new LinkedHashMap<>();
    private Long teamId;
    private Long otherTeamId;

    @BeforeEach
    void 実在主体とチームを準備する() {
        FeatureFlagTestSupport.enable(flags, cacheManager, FLAG);
        teamId = team("対象チーム").getId();
        otherTeamId = team("別チーム").getId();
        for (String actor : List.of("SYSTEM_ADMIN", "ADMIN", "DEPUTY_ADMIN", "ROLES_ONLY_ADMIN",
                "MEMBER", "SUPPORTER", "OTHER_TEAM_ADMIN", "OUTSIDER")) {
            Long userId = users.save(UserEntity.builder()
                    .email("shift-parent-" + UUID.randomUUID() + "@example.com")
                    .lastName("親存在").firstName(actor).displayName(actor)
                    .status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                    .locale("ja").timezone("Asia/Tokyo").build()).getId();
            actors.put(actor, userId);
            if (!List.of("SYSTEM_ADMIN", "ROLES_ONLY_ADMIN", "OUTSIDER").contains(actor)) {
                Long memberTeamId = actor.equals("OTHER_TEAM_ADMIN") ? otherTeamId : teamId;
                MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, memberTeamId,
                        actor.equals("SUPPORTER") ? RoleKind.SUPPORTER : RoleKind.MEMBER);
            }
            switch (actor) {
                case "SYSTEM_ADMIN" -> MembershipTestHelper.insertUserRole(em, userId, actor, null, null);
                case "ADMIN", "DEPUTY_ADMIN" ->
                        MembershipTestHelper.insertUserRole(em, userId, actor, teamId, null);
                case "ROLES_ONLY_ADMIN" -> MembershipTestHelper.insertUserRole(em, userId, "ADMIN", teamId, null);
                case "OTHER_TEAM_ADMIN" -> MembershipTestHelper.insertUserRole(em, userId, "ADMIN", otherTeamId, null);
                default -> { }
            }
        }
        em.flush();
        em.clear();
        assertThat(teams.findById(UNISSUED_TEAM_ID)).isEmpty();
    }

    @AfterEach
    void 共有キャッシュと認証を戻す() {
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "AC-01 SYSTEM_ADMIN、不在teamId={0}")
    @ValueSource(longs = {UNISSUED_TEAM_ID, 0L, -1L, Long.MAX_VALUE})
    void SYSTEM_ADMINでも不在チームへ作成できない(long targetTeamId) throws Exception {
        assertThat(teams.findById(targetTeamId)).isEmpty();
        assertRejected(request("SYSTEM_ADMIN", Long.toString(targetTeamId)), 403, "COMMON_002");
    }

    @Test
    @DisplayName("AC-02 SYSTEM_ADMINでも論理削除済みチームへ作成できない")
    void 論理削除済みチームを不在として拒否する() throws Exception {
        TeamEntity deleted = team("削除済みチーム");
        Long deletedTeamId = deleted.getId();
        deleted.softDelete();
        teams.saveAndFlush(deleted);
        em.clear();
        assertThat(teams.findById(deletedTeamId)).isEmpty();
        assertRejected(request("SYSTEM_ADMIN", deletedTeamId.toString()), 403, "COMMON_002");
    }

    @ParameterizedTest(name = "AC-03 SYSTEM_ADMINの所属={0}")
    @ValueSource(booleans = {true, false})
    void SYSTEM_ADMINは実在する所属非所属チームへ作成できる(boolean member) throws Exception {
        if (member) {
            MembershipTestHelper.insertMembership(em, actors.get("SYSTEM_ADMIN"), ScopeType.TEAM,
                    teamId, RoleKind.MEMBER);
            em.flush();
            em.clear();
        }
        assertCreated("SYSTEM_ADMIN");
    }

    @ParameterizedTest(name = "AC-04 既存管理者={0}")
    @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "ROLES_ONLY_ADMIN"})
    void 既存管理者の作成権限を保持する(String actor) throws Exception {
        assertCreated(actor);
    }

    @ParameterizedTest(name = "AC-05 権限不足={0}")
    @ValueSource(strings = {"MEMBER", "SUPPORTER", "OTHER_TEAM_ADMIN", "OUTSIDER"})
    void 権限不足は実在不在とも拒否する(String actor) throws Exception {
        assertRejected(request(actor, teamId.toString()), 403, "COMMON_002");
        assertRejected(request(actor, Long.toString(UNISSUED_TEAM_ID)), 403, "COMMON_002");
    }

    @Test
    @DisplayName("AC-06 匿名の作成は実Securityフィルタで401")
    void 匿名には作成を許可しない() throws Exception {
        // SecurityFilterChainのentry pointは空bodyの401を返すため、業務エラーコードは要求しない。
        assertRejected(request(null, teamId.toString()), 401, null);
    }

    @ParameterizedTest(name = "AC-07 teamId query={0}")
    @NullAndEmptySource
    void query欠落空文字を400にする(String query) throws Exception {
        assertRejected(request("SYSTEM_ADMIN", query), 400, "COMMON_001");
    }

    @Test
    @DisplayName("AC-07 文字列nullは既存teamId解決の404 COMMON_005")
    void query文字列nullの404を維持する() throws Exception {
        assertRejected(request("SYSTEM_ADMIN", "null"), 404, "COMMON_005");
    }

    @Test
    @DisplayName("AC-10 実在チームでは日付エラーSHIFT_010を維持")
    void 日付範囲違反では作成しない() throws Exception {
        assertRejected(request("ADMIN", teamId.toString())
                .content(BODY.replace("2030-04-07", "2030-03-31")), 400, "SHIFT_010");
    }

    @Test
    @DisplayName("AC-10 feature OFFは不在チームの作成より先に拒否")
    void featureGateの拒否を維持する() throws Exception {
        var flag = flags.findByFlagKey(FLAG).orElseThrow();
        flag.updateFlag(false, null);
        flags.saveAndFlush(flag);
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
        assertRejected(request("SYSTEM_ADMIN", Long.toString(UNISSUED_TEAM_ID)), 403, "FEATURE_GATE_001");
    }

    private void assertCreated(String actor) throws Exception {
        long before = schedules.count();
        MvcResult result = mockMvc.perform(request(actor, teamId.toString())).andReturn();
        em.flush();
        em.clear();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
        Long createdId = data.path("id").longValue();
        assertThat(data.path("teamId").longValue()).isEqualTo(teamId);
        assertThat(schedules.findById(createdId)).hasValueSatisfying(row -> {
            assertThat(row.getTeamId()).isEqualTo(teamId);
            assertThat(row.getCreatedBy()).isEqualTo(actors.get(actor));
        });
        assertThat(schedules.count()).isEqualTo(before + 1);
    }

    private void assertRejected(MockHttpServletRequestBuilder request, int expectedStatus, String expectedCode)
            throws Exception {
        long before = schedules.count();
        MvcResult result = mockMvc.perform(request).andReturn();
        em.flush();
        em.clear();
        // redでもstatusだけで打ち切らず、保存行の増分まで同時に観測する。
        assertAll(
                () -> assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus),
                () -> {
                    if (expectedCode != null) {
                        assertThat(mapper.readTree(result.getResponse().getContentAsString())
                                .path("error").path("code").asText()).isEqualTo(expectedCode);
                    }
                },
                () -> assertThat(schedules.count()).isEqualTo(before));
    }

    private MockHttpServletRequestBuilder request(String actor, String query) {
        var request = post(BASE).header("Accept-Language", "ja")
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (query != null) {
            request.param("teamId", query);
        }
        if (actor != null) {
            List<String> roles = actor.equals("SYSTEM_ADMIN")
                    ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER");
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actors.get(actor), roles));
        }
        return request;
    }

    private TeamEntity team(String name) {
        return teams.save(TeamEntity.builder()
                .slug("parent-" + UUID.randomUUID().toString().substring(0, 20))
                .name(name).visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true).build());
    }
}
