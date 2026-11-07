package com.mannschaft.app.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260821-1027: TeamEquipmentController#getEquipment の非所属時の存在秘匿。
 * 実ログインCookie・実Securityフィルタ・共通MySQLを通す。Redis外部境界のみ基底モックを使う。
 * 非所属の実在子は修正前RED、在籍・欠落・親状態・401/400は既契約の回帰対照。
 * GET前後のDB比較はログイン後、今回所有する備品・TEAM・所属/ロール行に限定する。
 * login監査/通知の非同期完了や全table不変、afterCommit配送の実証とは扱わない。
 * Docker欠落・skip・compile失敗をRED/GREENとして数えない。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("TEAM備品詳細GETの同一404契約")
class EquipmentTeamDetailHttpContractIT extends AbstractMySqlIntegrationTest {
    private static final String PASSWORD = "EquipmentDetail1!";
    private static final String NOT_FOUND = "{\"error\":{\"code\":\"EQUIPMENT_001\","
            + "\"message\":\"備品が見つかりません\",\"fieldErrors\":[]}}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void 外部Redisと既存機能フラグを用意する() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_FACILITY_ENABLED");
    }

    @ParameterizedTest(name = "非所属404本文 {0}")
    @ValueSource(strings = {"ja", "en"})
    void 非所属と脱退と別TEAM管理者は子の有無によらず同じ404(String locale) throws Exception {
        Fixture f = fixture();
        JsonNode expected = objectMapper.readTree(NOT_FOUND);
        for (UserEntity actor : List.of(f.outsider(), f.left(), f.foreignAdmin())) {
            Cookie cookie = login(actor);
            for (long itemId : List.of(f.local(), f.missing(), f.foreign(), f.deleted())) {
                Map<String, List<Map<String, Object>>> before = snapshot(f);
                MvcResult result = read(f.team(), itemId, cookie, locale);
                Map<String, List<Map<String, Object>>> after = snapshot(f);
                assertAll(
                        () -> assertThat(result.getResponse().getStatus()).isEqualTo(404),
                        () -> assertThat(body(result)).isEqualTo(expected),
                        () -> assertThat(after).isEqualTo(before));
            }
        }
    }

    @ParameterizedTest(name = "親状態 {0}/在籍={1}")
    @MethodSource("parentMembershipCases")
    void 親状態に依存せず在籍者の既契約と非在籍者の同一404を維持する(Parent parent, boolean member) throws Exception {
        Fixture f = fixture();
        TeamEntity team = em.find(TeamEntity.class, f.team());
        if (parent == Parent.DELETED) {
            team.softDelete();
        } else if (parent == Parent.ARCHIVED) {
            team.archive();
        } else if (parent == Parent.PROVISIONED) {
            // 既フィールドの状態をfixtureとして再現する。新しい本番遷移は追加しない。
            ReflectionTestUtils.setField(team, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED);
        } else if (parent == Parent.ABSENT) {
            em.remove(team);
        }
        flush();
        Cookie cookie = login(member ? f.member() : f.outsider());
        Map<String, List<Map<String, Object>>> before = snapshot(f);
        MvcResult existing = read(f.team(), f.local(), cookie, "ja");
        assertThat(existing.getResponse().getStatus()).isEqualTo(member ? 200 : 404);
        if (member) {
            assertThat(body(existing).path("data").path("id").asLong()).isEqualTo(f.local());
        } else {
            assertThat(body(existing)).isEqualTo(objectMapper.readTree(NOT_FOUND));
        }
        for (long itemId : List.of(f.missing(), f.foreign(), f.deleted())) {
            MvcResult absent = read(f.team(), itemId, cookie, "ja");
            assertThat(absent.getResponse().getStatus()).isEqualTo(404);
            assertThat(body(absent)).isEqualTo(objectMapper.readTree(NOT_FOUND));
        }
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ja", "en"})
    void 未知TEAMと認証とLong型境界を保持する(String locale) throws Exception {
        Fixture f = fixture();
        TeamEntity missingTeam = team();
        long missingTeamId = missingTeam.getId();
        em.remove(missingTeam);
        flush();
        Cookie cookie = login(f.member());
        MvcResult unknown = read(missingTeamId, f.local(), cookie, locale);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(unknown)).isEqualTo(objectMapper.readTree(NOT_FOUND));
        String url = "/api/v1/teams/" + f.team() + "/equipment/" + f.local();
        mockMvc.perform(get(url).servletPath(url)).andExpect(status().isUnauthorized());
        String badItem = "/api/v1/teams/" + f.team() + "/equipment/no-long";
        MvcResult itemMismatch = mockMvc.perform(get(badItem).servletPath(badItem).cookie(cookie)).andReturn();
        assertThat(itemMismatch.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(itemMismatch).path("error").path("code").asText()).isEqualTo("COMMON_001");
        // teamIdの非数値入力はslugとして解決される。未解決slugは既handlerの404であり型400ではない。
        String badTeam = "/api/v1/teams/eq404-unknown-" + UUID.randomUUID() + "/equipment/" + f.local();
        MvcResult unresolved = mockMvc.perform(get(badTeam).servletPath(badTeam).cookie(cookie)).andReturn();
        assertThat(unresolved.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(unresolved).path("error").path("code").asText()).isEqualTo("COMMON_005");
    }

    private Fixture fixture() {
        UserEntity member = user();
        UserEntity outsider = user();
        UserEntity left = user();
        UserEntity foreignAdmin = user();
        TeamEntity local = team();
        TeamEntity foreign = team();
        MembershipTestHelper.insertMembership(em, member.getId(), ScopeType.TEAM, local.getId(), RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, left.getId(), ScopeType.TEAM, local.getId(), RoleKind.MEMBER);
        em.createNativeQuery("UPDATE memberships SET left_at = UTC_TIMESTAMP() WHERE user_id = :id")
                .setParameter("id", left.getId()).executeUpdate();
        MembershipTestHelper.insertMembership(em, foreignAdmin.getId(), ScopeType.TEAM, foreign.getId(), RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, foreignAdmin.getId(), "ADMIN", foreign.getId(), null);
        EquipmentItemEntity present = item(local.getId());
        EquipmentItemEntity foreignItem = item(foreign.getId());
        EquipmentItemEntity deleted = item(local.getId());
        deleted.softDelete();
        EquipmentItemEntity missing = item(local.getId());
        long missingId = missing.getId();
        em.remove(missing);
        flush();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM equipment_items WHERE id = ?", Long.class, missingId)).isZero();
        return new Fixture(local.getId(), foreign.getId(), present.getId(), foreignItem.getId(), deleted.getId(), missingId,
                member, outsider, left, foreignAdmin);
    }

    private UserEntity user() {
        UserEntity user = UserEntity.builder().email("eq404-" + UUID.randomUUID() + "@example.com")
                .passwordHash(passwordEncoder.encode(PASSWORD)).lastName("備品").firstName("試練")
                .displayName("備品404試練").status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").build();
        em.persist(user);
        return user;
    }

    private TeamEntity team() {
        TeamEntity team = TeamEntity.builder().name("備品404試練")
                .slug("eq404-" + UUID.randomUUID().toString().substring(0, 20))
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build();
        em.persist(team);
        return team;
    }

    private EquipmentItemEntity item(long teamId) {
        EquipmentItemEntity item = EquipmentItemEntity.builder().teamId(teamId).name("備品404試練")
                .quantity(1).assignedQuantity(0).status(EquipmentStatus.AVAILABLE).isConsumable(false)
                .qrCode("eq404-" + UUID.randomUUID()).build();
        em.persist(item);
        return item;
    }

    private Cookie login(UserEntity user) throws Exception {
        String url = "/api/v1/auth/login";
        MvcResult login = mockMvc.perform(post(url).servletPath(url).header("User-Agent", "Equipment404/1.0")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "email", user.getEmail(), "password", PASSWORD, "rememberMe", false))))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("access_token");
        assertThat(cookie).isNotNull();
        assertThat(body(login).path("data").path("userId").asLong()).isEqualTo(user.getId());
        flush();
        return cookie;
    }

    private MvcResult read(long team, long item, Cookie cookie, String locale) throws Exception {
        String url = "/api/v1/teams/" + team + "/equipment/" + item;
        return mockMvc.perform(get(url).servletPath(url).cookie(cookie).header("Accept-Language", locale)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void flush() {
        em.flush();
        em.clear();
    }

    private Map<String, List<Map<String, Object>>> snapshot(Fixture f) {
        flush();
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("items", ownedRows("SELECT * FROM equipment_items WHERE id IN (?, ?, ?, ?) ORDER BY id",
                f.local(), f.foreign(), f.deleted(), f.missing()));
        rows.put("assignments", ownedRows("SELECT * FROM equipment_assignments WHERE equipment_item_id IN (?, ?, ?, ?) ORDER BY id",
                f.local(), f.foreign(), f.deleted(), f.missing()));
        rows.put("teams", ownedRows("SELECT * FROM teams WHERE id IN (?, ?) ORDER BY id", f.team(), f.foreignTeam()));
        rows.put("memberships", ownedRows("SELECT * FROM memberships WHERE scope_type = 'TEAM' AND scope_id IN (?, ?) "
                        + "AND user_id IN (?, ?, ?, ?) ORDER BY id", f.team(), f.foreignTeam(),
                f.member().getId(), f.outsider().getId(), f.left().getId(), f.foreignAdmin().getId()));
        rows.put("roles", ownedRows("SELECT * FROM user_roles WHERE team_id IN (?, ?) "
                        + "AND user_id IN (?, ?, ?, ?) ORDER BY id", f.team(), f.foreignTeam(),
                f.member().getId(), f.outsider().getId(), f.left().getId(), f.foreignAdmin().getId()));
        return rows;
    }

    private List<Map<String, Object>> ownedRows(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        // JDBCがbyte[]で返す列がある場合だけ内容値へ直し、Map.equalsの参照比較による偽FAILを防ぐ。
        rows.forEach(row -> row.replaceAll((column, value) -> value instanceof byte[] bytes
                ? Base64.getEncoder().encodeToString(bytes) : value));
        return rows;
    }

    private enum Parent { ACTIVE, DELETED, ARCHIVED, PROVISIONED, ABSENT }

    private static Stream<Arguments> parentMembershipCases() {
        return Stream.of(Parent.values()).flatMap(parent -> Stream.of(true, false)
                .map(member -> Arguments.of(parent, member)));
    }

    private record Fixture(long team, long foreignTeam, long local, long foreign, long deleted, long missing,
                           UserEntity member, UserEntity outsider, UserEntity left, UserEntity foreignAdmin) { }
}
