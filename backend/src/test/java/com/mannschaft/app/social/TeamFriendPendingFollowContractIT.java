package com.mannschaft.app.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.RolePermissionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261021-0020: 片方向フォロー一覧の実HTTP契約を検証する試練。
 *
 * <p>実JWT・Securityフィルタ・ScopeSlugIdConverter・業務Bean・Testcontainers MySQLを通す。
 * pendingは未実装のため期待200に対する404がred。既存follow/unfollowの認可・状態境界は
 * 初回からgreenでよい回帰固定である。通知配送は観測せず、所有fixtureをテストTXでrollbackする。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261021-0020 フォロー中一覧のHTTP契約")
class TeamFriendPendingFollowContractIT extends AbstractMySqlIntegrationTest {

    private static final String PERMISSION = "MANAGE_FRIEND_TEAMS";
    private static final String BASE = "/api/v1/teams/{id}/friends";
    private static final LocalDateTime SAME_SECOND = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final long SYNTHETIC_TARGET_START = 10_000_000L;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private MembershipRepository memberships;
    @Autowired private PermissionRepository permissions;
    @Autowired private RolePermissionRepository rolePermissions;
    @Autowired private PermissionGroupRepository groups;
    @Autowired private PermissionGroupPermissionRepository groupPermissions;
    @Autowired private UserPermissionGroupRepository userGroups;
    @Autowired private FollowRepository follows;
    @Autowired private TeamFriendRepository friends;
    @PersistenceContext private EntityManager em;

    private TeamEntity own;
    private TeamEntity target;
    private Long admin;
    private PermissionEntity permission;
    private final java.util.Set<Long> systemActors = new HashSet<>();

    @BeforeEach
    void 実権限と所有fixtureを作成する() {
        SecurityContextHolder.clearContext();
        reset(redisTemplate);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        own = team();
        target = team();
        permission = permissions.findByNameIn(List.of(PERMISSION)).stream().findFirst()
                .orElseGet(() -> permissions.save(PermissionEntity.builder().name(PERMISSION)
                        .displayName("フレンド管理").scope(PermissionEntity.Scope.TEAM).build()));
        RoleEntity adminRole = role("ADMIN");
        if (rolePermissions.findByRoleId(adminRole.getId()).stream()
                .noneMatch(row -> row.getPermissionId().equals(permission.getId()) && row.getIsDefault())) {
            rolePermissions.save(RolePermissionEntity.builder().roleId(adminRole.getId())
                    .permissionId(permission.getId()).isDefault(true).build());
        }
        admin = actor("ADMIN", own.getId(), false);
        em.flush();
    }

    @AfterEach
    void 認証状態を復元する() {
        reset(redisTemplate);
        SecurityContextHolder.clearContext();
    }

    @Test
    void AC1_片方向は表示され相互成立するとフレンド一覧へ移る() throws Exception {
        JsonNode created = follow(admin, own, target).path("data");
        assertThat(created.path("mutual").asBoolean()).isFalse();
        JsonNode pending = pending(admin, own, 0, 20);
        assertThat(pending.path("data").size()).isEqualTo(1);
        assertThat(pending.path("data").get(0).path("followId").asLong())
                .isEqualTo(created.path("followId").asLong());
        assertThat(pending.path("data").get(0).path("followedBack").asBoolean()).isFalse();
        Long otherAdmin = actor("ADMIN", target.getId(), false);
        assertThat(follow(otherAdmin, target, own).path("data").path("mutual").asBoolean()).isTrue();
        assertThat(pending(admin, own, 0, 20).path("data").isEmpty()).isTrue();
        assertThat(ok(auth(get(BASE, own.getSlug()), admin)).path("data").size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC2_双方向のみ残る行を取消し有効相手は再フォローで成立する(boolean invalid) throws Exception {
        followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId());
        followRow(FollowerType.TEAM, target.getId(), FollowerType.TEAM, own.getId());
        if (invalid) { target.archive(); em.flush(); }
        JsonNode row = pending(admin, own, 0, 20).path("data").get(0);
        assertThat(row.path("followedBack").asBoolean()).isTrue();
        assertThat(row.path("nameHidden").asBoolean()).isEqualTo(invalid);
        unfollow(admin, own, target);
        assertThat(pending(admin, own, 0, 20).path("data").isEmpty()).isTrue();
        MvcResult result = sendFollow(admin, own, target, false);
        assertThat(result.getResponse().getStatus()).isEqualTo(invalid ? 404 : 201);
        if (invalid) { assertThat(json(result).path("error").path("code").asText()).isEqualTo("SOCIAL_104"); }
        else { assertThat(json(result).path("data").path("mutual").asBoolean()).isTrue(); }
    }

    @Test
    void AC3_解除後に相手側だけに残るフォローは自一覧に混入しない() throws Exception {
        follow(admin, own, target);
        follow(actor("ADMIN", target.getId(), false), target, own);
        unfollow(admin, own, target);
        assertThat(pending(admin, own, 0, 20).path("pagination").path("totalElements").asLong()).isZero();
        assertThat(follows.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                FollowerType.TEAM, target.getId(), FollowerType.TEAM, own.getId())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DEPUTY_GROUP", "MEMBER_GROUP", "SYSTEM_ADMIN_GROUP"})
    void AC4_実効権限を持つ者は一覧と既存フォロー取消を同じく許可する(String label) throws Exception {
        Long user = authorizedActor(label);
        assertThat(pending(user, own, 0, 20).path("pagination").path("totalElements").asLong()).isZero();
        assertThat(follow(user, own, target).path("data").path("followId").asLong()).isPositive();
        unfollow(user, own, target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPUTY_ADMIN", "MEMBER", "SUPPORTER", "GUEST", "OUTSIDER", "OTHER_ADMIN", "SYSTEM_ADMIN"})
    void AC4_権限なしは一覧と既存フォロー取消を全て403にする(String label) throws Exception {
        Long user = unauthorizedActor(label);
        followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId());
        assertStatus(auth(get(BASE + "/pending", own.getSlug()), user), 403);
        assertThat(sendFollow(user, own, target, false).getResponse().getStatus()).isEqualTo(403);
        assertStatus(auth(delete(BASE + "/follow/{target}", own.getSlug(), target.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pastForwardHandling\":\"KEEP\"}"), user), 403);
        assertThat(follows.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId())).isTrue();
    }

    @Test
    void AC4_未ログインは401で不在slugは404になる() throws Exception {
        assertStatus(get(BASE + "/pending", own.getSlug()), 401);
        assertStatus(auth(get(BASE + "/pending", "missing-" + unique()), admin), 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "ARCHIVED", "PROVISIONED", "DELETED"})
    void AC5_状態を変えても行と総数を維持し名前とslugだけを伏せる(String state) throws Exception {
        followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId());
        JsonNode before = pending(admin, own, 0, 20);
        switch (state) {
            case "PRIVATE" -> ReflectionTestUtils.setField(target, "visibility", TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            case "ARCHIVED" -> target.archive();
            case "PROVISIONED" -> ReflectionTestUtils.setField(target, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED);
            case "DELETED" -> target.softDelete();
            default -> throw new IllegalArgumentException(state);
        }
        membership(admin, target.getId(), RoleKind.MEMBER);
        em.flush();
        em.clear();
        JsonNode after = pending(admin, own, 0, 20);
        assertThat(after.path("pagination")).isEqualTo(before.path("pagination"));
        JsonNode row = after.path("data").get(0);
        assertThat(row.path("targetTeamId").asLong()).isEqualTo(target.getId());
        assertThat(row.path("nameHidden").asBoolean()).isTrue();
        assertThat(row.has("targetTeamName") && row.get("targetTeamName").isNull()).isTrue();
        assertThat(row.has("targetTeamSlug") && row.get("targetTeamSlug").isNull()).isTrue();
        assertThat(pending(admin, own, Integer.MAX_VALUE, 100).path("pagination").path("totalElements").asLong())
                .isEqualTo(1);
        unfollow(admin, own, target);
    }

    @Test
    void AC6_公開有効相手に限り名前とslugとフォロー日時を返す() throws Exception {
        FollowEntity row = followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId());
        JsonNode actual = pending(admin, own, 0, 20).path("data").get(0);
        assertThat(actual.path("followId").asLong()).isEqualTo(row.getId());
        assertThat(actual.path("nameHidden").asBoolean()).isFalse();
        assertThat(actual.path("targetTeamName").asText()).isEqualTo(target.getName());
        assertThat(actual.path("targetTeamSlug").asText()).isEqualTo(target.getSlug());
        assertThat(actual.path("followedAt").asText()).isNotBlank();
        assertThat(actual.has("isNameHidden")).isFalse();
        assertThat(actual.has("isFollowedBack")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {999, 1000, 1001})
    void AC7_同一秒の千件境界でページ数が正しく重複欠落しない(int count) throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            expected.add(followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM,
                    SYNTHETIC_TARGET_START + i).getId());
        }
        em.flush();
        em.createQuery("UPDATE FollowEntity f SET f.createdAt = :at WHERE f.followerType = :type AND f.followerId = :id")
                .setParameter("at", SAME_SECOND).setParameter("type", FollowerType.TEAM)
                .setParameter("id", own.getId()).executeUpdate();
        em.clear();
        Collections.reverse(expected);
        List<Long> actual = new ArrayList<>();
        int pages = (count + 99) / 100;
        for (int page = 0; page < pages; page++) {
            JsonNode result = pending(admin, own, page, 100);
            assertThat(result.path("pagination").path("totalElements").asLong()).isEqualTo(count);
            assertThat(result.path("pagination").path("totalPages").asInt()).isEqualTo(pages);
            assertThat(result.path("pagination").path("hasNext").asBoolean()).isEqualTo(page < pages - 1);
            assertThat(result.path("data").size()).isEqualTo(Math.min(100, count - page * 100));
            result.path("data").forEach(row -> actual.add(row.path("followId").asLong()));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(new HashSet<>(actual)).hasSize(count);
    }

    @ParameterizedTest
    @CsvSource({"-1,20,0,20", "0,0,0,1", "0,-1,0,1", "0,101,0,100", "0,100,0,100"})
    void AC8_pageとsizeを仕様の境界へ補正する(int page, int size, int expectedPage, int expectedSize) throws Exception {
        JsonNode result = pending(admin, own, page, size);
        assertThat(result.path("pagination").path("page").asInt()).isEqualTo(expectedPage);
        assertThat(result.path("pagination").path("size").asInt()).isEqualTo(expectedSize);
    }

    @ParameterizedTest
    @CsvSource({"99,20", "2147483647,1", "2147483647,100"})
    void AC8_範囲外ページは空配列と正しい総数を返す(int page, int size) throws Exception {
        followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId());
        JsonNode result = pending(admin, own, page, size);
        assertThat(result.path("data").isEmpty()).isTrue();
        assertThat(result.path("pagination").path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("pagination").path("page").asInt()).isEqualTo(page);
        assertThat(result.path("pagination").path("size").asInt()).isEqualTo(size);
        assertThat(result.path("pagination").path("hasNext").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"ARCHIVED,false", "ARCHIVED,true", "PROVISIONED,false", "PROVISIONED,true"})
    void AC11_既存数値とslug経路は無効相手を404で拒否する(String state, boolean slug) throws Exception {
        if (state.equals("ARCHIVED")) { target.archive(); }
        else { ReflectionTestUtils.setField(target, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED); }
        em.flush();
        MvcResult result = sendFollow(admin, own, target, slug);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(result).path("error").path("code").asText()).isEqualTo("SOCIAL_104");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ORGANIZATION"})
    void AC22_同じ数値IDの逆向き異種フォローはfollowedBackにならない(String type) throws Exception {
        followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId());
        followRow(FollowerType.valueOf(type), target.getId(), FollowerType.TEAM, own.getId());
        followRow(FollowerType.TEAM, target.getId(), FollowerType.valueOf(type), own.getId());
        assertThat(pending(admin, own, 0, 20).path("data").get(0).path("followedBack").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void AC23_正規化ペアは自チームIDの大小によらず相互を除外する(boolean reverse) throws Exception {
        TeamEntity first = reverse ? target : own;
        TeamEntity second = reverse ? own : target;
        Long firstAdmin = reverse ? actor("ADMIN", target.getId(), false) : admin;
        follow(firstAdmin, first, second);
        follow(reverse ? admin : actor("ADMIN", target.getId(), false), second, first);
        assertThat(pending(firstAdmin, first, 0, 20).path("pagination").path("totalElements").asLong()).isZero();
        assertThat(pending(firstAdmin, first, Integer.MAX_VALUE, 100).path("pagination").path("totalElements").asLong())
                .isZero();
    }

    @Test
    void AC24_一覧取得後に成立した相互関係の取消は自フォローとフレンドだけを消す() throws Exception {
        follow(admin, own, target);
        assertThat(pending(admin, own, 0, 20).path("data").size()).isEqualTo(1);
        follow(actor("ADMIN", target.getId(), false), target, own);
        unfollow(admin, own, target);
        assertThat(follows.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId())).isFalse();
        assertThat(follows.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                FollowerType.TEAM, target.getId(), FollowerType.TEAM, own.getId())).isTrue();
        assertThat(friends.findByTeamAIdAndTeamBId(Math.min(own.getId(), target.getId()),
                Math.max(own.getId(), target.getId()))).isEmpty();
    }

    @Test
    void AC25_自チームの異種と別チームのフォローをdataにもCOUNTにも混ぜない() throws Exception {
        followRow(FollowerType.TEAM, own.getId(), FollowerType.USER, target.getId());
        followRow(FollowerType.TEAM, own.getId(), FollowerType.ORGANIZATION, target.getId());
        followRow(FollowerType.USER, own.getId(), FollowerType.TEAM, target.getId());
        followRow(FollowerType.ORGANIZATION, own.getId(), FollowerType.TEAM, target.getId());
        followRow(FollowerType.TEAM, target.getId(), FollowerType.TEAM, own.getId());
        JsonNode empty = pending(admin, own, 0, 20);
        assertThat(empty.path("data").isArray()).isTrue();
        assertThat(empty.path("data").isEmpty()).isTrue();
        assertThat(empty.path("pagination").path("totalElements").asLong()).isZero();
        assertThat(pending(admin, own, Integer.MAX_VALUE, 100).path("pagination").path("totalElements").asLong()).isZero();
        FollowEntity correct = followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId());
        JsonNode actual = pending(admin, own, 0, 20);
        assertThat(actual.path("data").size()).isEqualTo(1);
        assertThat(actual.path("data").get(0).path("followId").asLong()).isEqualTo(correct.getId());
        assertThat(actual.path("pagination").path("totalElements").asLong()).isEqualTo(1);
        assertThat(pending(admin, own, Integer.MAX_VALUE, 100).path("pagination").path("totalElements").asLong())
                .isEqualTo(1);
    }


    @Test
    void AC13_千一件の実SQLをEXPLAINしページ取得時間を記録する() throws Exception {
        for (int i = 0; i < 1001; i++) {
            followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, SYNTHETIC_TARGET_START + i);
        }
        em.flush();
        em.clear();
        var sqlLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate.SQL");
        var previousLevel = sqlLogger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            long started = System.nanoTime();
            JsonNode response = pending(admin, own, 0, 100);
            long elapsedNanos = System.nanoTime() - started;
            assertThat(response.path("pagination").path("totalElements").asLong()).isEqualTo(1001);
            assertThat(response.path("data").size()).isEqualTo(100);
            var statements = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(sql -> sql.contains(" from follows ") && sql.contains("team_friends"))
                    .toList();
            assertThat(statements).as("一覧とCOUNTの実SQL").hasSize(2);
            var plans = new ArrayList<Map<String, Object>>();
            for (String sql : statements) {
                var explain = em.createNativeQuery("EXPLAIN " + sql);
                int parameters = (int) sql.chars().filter(c -> c == '?').count();
                for (int index = 1; index <= parameters; index++) {
                    explain.setParameter(index, own.getId());
                }
                String compact = sql.replaceAll("\\s+", "");
                if (compact.endsWith("limit?,?")) {
                    explain.setParameter(parameters - 1, 0);
                    explain.setParameter(parameters, 100);
                } else if (compact.endsWith("limit?")) {
                    explain.setParameter(parameters, 100);
                }
                @SuppressWarnings("unchecked")
                List<Object[]> rows = explain.getResultList();
                plans.add(Map.of("sql", sql, "explain", rows.stream()
                        .map(java.util.Arrays::asList).toList()));
            }
            var report = java.nio.file.Path.of("build/reports/pending-follow/performance.json");
            java.nio.file.Files.createDirectories(report.getParent());
            java.nio.file.Files.writeString(report, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("rows", 1001, "pageSize", 100, "elapsedMs", elapsedNanos / 1_000_000.0,
                            "queries", plans)), StandardCharsets.UTF_8);
        } finally {
            sqlLogger.detachAppender(appender);
            appender.stop();
            sqlLogger.setLevel(previousLevel);
        }
    }

    private JsonNode pending(Long actor, TeamEntity source, int page, int size) throws Exception {
        return ok(auth(get(BASE + "/pending", source.getSlug()).param("page", String.valueOf(page))
                .param("size", String.valueOf(size)), actor));
    }

    private JsonNode follow(Long actor, TeamEntity source, TeamEntity destination) throws Exception {
        MvcResult result = sendFollow(actor, source, destination, false);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json(result);
    }

    private MvcResult sendFollow(Long actor, TeamEntity source, TeamEntity destination, boolean slug) throws Exception {
        Map<String, Object> body = slug ? Map.of("targetTeamSlug", destination.getSlug())
                : Map.of("targetTeamId", destination.getId());
        return mvc.perform(auth(post(BASE + "/follow", source.getSlug())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)), actor)).andReturn();
    }

    private void unfollow(Long actor, TeamEntity source, TeamEntity destination) throws Exception {
        assertStatus(auth(delete(BASE + "/follow/{target}", source.getSlug(), destination.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pastForwardHandling\":\"KEEP\"}"), actor), 204);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as("未実装pending URLはここで404になりred").isEqualTo(200);
        return json(result);
    }

    private void assertStatus(MockHttpServletRequestBuilder request, int expected) throws Exception {
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(expected);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Long actor) {
        return request.header("Accept-Language", "ja").header("Authorization", "Bearer "
                + tokens.issueAccessToken(actor, systemActors.contains(actor)
                        ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER")));
    }

    private TeamEntity team() {
        return teams.save(TeamEntity.builder().slug("pending-" + unique()).name("フォロー契約" + unique())
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build());
    }

    private RoleEntity role(String name) {
        return roles.findByName(name).orElseGet(() -> roles.save(RoleEntity.builder().name(name).displayName(name)
                .priority(RolePriority.priority(name)).isSystem(name.equals("SYSTEM_ADMIN")).build()));
    }

    private Long actor(String name, Long teamId, boolean group) {
        Long id = users.save(UserEntity.builder().email("pending-" + UUID.randomUUID() + "@example.com")
                .lastName("契約").firstName("試験").displayName("フォロー試験").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        if ("SYSTEM_ADMIN".equals(name)) { systemActors.add(id); }
        if (name != null) {
            userRoles.save(UserRoleEntity.builder().userId(id).roleId(role(name).getId()).teamId(teamId).build());
        }
        if (teamId != null && !"GUEST".equals(name)) {
            membership(id, teamId, "SUPPORTER".equals(name) ? RoleKind.SUPPORTER : RoleKind.MEMBER);
        }
        if (group) {
            PermissionGroupEntity row = groups.save(PermissionGroupEntity.builder().teamId(teamId)
                    .name("フォロー権限" + unique()).targetRole("DEPUTY_ADMIN".equals(name)
                            ? PermissionGroupEntity.TargetRole.DEPUTY_ADMIN : PermissionGroupEntity.TargetRole.MEMBER).build());
            groupPermissions.save(PermissionGroupPermissionEntity.builder().groupId(row.getId())
                    .permissionId(permission.getId()).build());
            userGroups.save(UserPermissionGroupEntity.builder().userId(id).groupId(row.getId()).build());
        }
        em.flush();
        return id;
    }

    private Long authorizedActor(String label) {
        if (label.equals("ADMIN")) { return admin; }
        if (label.equals("DEPUTY_GROUP")) { return actor("DEPUTY_ADMIN", own.getId(), true); }
        Long id = actor("MEMBER", own.getId(), true);
        if (label.equals("SYSTEM_ADMIN_GROUP")) {
            systemActors.add(id);
            userRoles.save(UserRoleEntity.builder().userId(id).roleId(role("SYSTEM_ADMIN").getId()).build());
            em.flush();
        }
        return id;
    }

    private Long unauthorizedActor(String label) {
        if (label.equals("OUTSIDER")) { return actor(null, null, false); }
        if (label.equals("OTHER_ADMIN")) { return actor("ADMIN", team().getId(), false); }
        return actor(label, label.equals("SYSTEM_ADMIN") ? null : own.getId(), false);
    }

    private void membership(Long actor, Long teamId, RoleKind kind) {
        memberships.save(MembershipEntity.builder().userId(actor).scopeType(ScopeType.TEAM).scopeId(teamId)
                .roleKind(kind).joinedAt(LocalDateTime.now()).build());
    }

    private FollowEntity followRow(FollowerType fromType, Long from, FollowerType toType, Long to) {
        return follows.save(FollowEntity.builder().followerType(fromType).followerId(from)
                .followedType(toType).followedId(to).build());
    }

    private String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
