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
@DisplayName("CMP-261024-2051 受信申請一覧のHTTP契約")
class TeamFriendIncomingFollowContractIT extends AbstractMySqlIntegrationTest {

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
    void FR09_受信だけを表示し成立後に除外する() throws Exception {
        Long sender = actor("ADMIN", target.getId(), false);
        JsonNode created = follow(sender, target, own).path("data");
        JsonNode row = pending(admin, own, 0, 20).path("data").get(0);
        assertThat(row.path("followId").asLong()).isEqualTo(created.path("followId").asLong());
        assertThat(row.path("sourceTeamId").asLong()).isEqualTo(target.getId());
        assertThat(row.path("sourceTeamSlug").asText()).isEqualTo(target.getSlug());
        assertThat(row.path("followedBack").asBoolean()).isFalse();
        JsonNode sent = ok(auth(get(BASE + "/pending", target.getSlug()), sender)).path("data").get(0);
        assertThat(row.path("followedAt")).isEqualTo(sent.path("followedAt"));
        assertThat(row.path("followedAt").asText()).endsWith("Z");
        assertThat(follow(admin, own, target).path("data").path("mutual").asBoolean()).isTrue();
        assertThat(pending(admin, own, 0, 20).path("data").isEmpty()).isTrue();
        assertThat(ok(auth(get(BASE, own.getSlug()), admin)).path("data").size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "ARCHIVED", "PROVISIONED", "DELETED"})
    void FR10_無効非公開元は所属していても名前を伏せ行を保持する(String state) throws Exception {
        followRow(FollowerType.TEAM, target.getId(), FollowerType.TEAM, own.getId());
        switch (state) {
            case "PRIVATE" -> ReflectionTestUtils.setField(target, "visibility", TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            case "ARCHIVED" -> target.archive();
            case "PROVISIONED" -> ReflectionTestUtils.setField(target, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED);
            case "DELETED" -> target.softDelete();
            default -> throw new IllegalArgumentException(state);
        }
        membership(admin, target.getId(), RoleKind.MEMBER); em.flush(); em.clear();
        JsonNode result = pending(admin, own, 0, 20);
        assertThat(result.path("pagination").path("totalElements").asLong()).isEqualTo(1);
        JsonNode row = result.path("data").get(0);
        assertThat(row.path("nameHidden").asBoolean()).isTrue();
        assertThat(row.has("sourceTeamName") && row.path("sourceTeamName").isNull()).isTrue();
        assertThat(row.has("sourceTeamSlug") && row.path("sourceTeamSlug").isNull()).isTrue();
    }

    @Test
    void FR09_TEAM受信のみで異種と送信を混ぜず逆followを判定する() throws Exception {
        followRow(FollowerType.USER, target.getId(), FollowerType.TEAM, own.getId());
        followRow(FollowerType.TEAM, target.getId(), FollowerType.USER, own.getId());
        followRow(FollowerType.TEAM, own.getId(), FollowerType.TEAM, target.getId());
        assertThat(pending(admin, own, 0, 20).path("data").isEmpty()).isTrue();
        followRow(FollowerType.TEAM, target.getId(), FollowerType.TEAM, own.getId());
        JsonNode result = pending(admin, own, 0, 20);
        assertThat(result.path("pagination").path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("data").get(0).path("followedBack").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPUTY_ADMIN", "MEMBER", "SUPPORTER", "GUEST", "OUTSIDER", "OTHER_ADMIN", "SYSTEM_ADMIN"})
    void FR10_受信チーム権限なしは403(String label) throws Exception {
        assertStatus(auth(get(BASE + "/incoming", own.getSlug()), unauthorizedActor(label)), 403);
    }

    @Test
    void FR10_未認証は401() throws Exception { assertStatus(get(BASE + "/incoming", own.getSlug()), 401); }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DEPUTY_GROUP", "MEMBER_GROUP", "SYSTEM_ADMIN_GROUP"})
    void FR10_実効権限者だけ受信を取得できる(String label) throws Exception {
        assertThat(pending(authorizedActor(label), own, 0, 20).path("data").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"-1,20,0,20", "0,0,0,1", "0,101,0,100"})
    void FR10_受信ページ境界を補正する(int page, int size, int expectedPage, int expectedSize) throws Exception {
        JsonNode result = pending(admin, own, page, size).path("pagination");
        assertThat(result.path("page").asInt()).isEqualTo(expectedPage);
        assertThat(result.path("size").asInt()).isEqualTo(expectedSize);
    }

    @Test
    void FR15_同時刻二十一件をID降順で欠落せず返し巨大offsetでも総数を返す() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 21; i++) ids.add(followRow(FollowerType.TEAM, SYNTHETIC_TARGET_START + i, FollowerType.TEAM, own.getId()).getId());
        em.flush();
        em.createQuery("UPDATE FollowEntity f SET f.createdAt = :at WHERE f.followedType = :type AND f.followedId = :id")
            .setParameter("at", SAME_SECOND).setParameter("type", FollowerType.TEAM).setParameter("id", own.getId()).executeUpdate(); em.clear();
        Collections.reverse(ids); List<Long> actual = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            JsonNode result = pending(admin, own, page, 20);
            assertThat(result.path("pagination").path("totalElements").asLong()).isEqualTo(21);
            result.path("data").forEach(row -> actual.add(row.path("followId").asLong()));
        }
        assertThat(actual).containsExactlyElementsOf(ids);
        JsonNode beyond = pending(admin, own, Integer.MAX_VALUE, 100);
        assertThat(beyond.path("data").isEmpty()).isTrue();
        assertThat(beyond.path("pagination").path("totalElements").asLong()).isEqualTo(21);
    }

    private JsonNode pending(Long actor, TeamEntity source, int page, int size) throws Exception {
        return ok(auth(get(BASE + "/incoming", source.getSlug()).param("page", String.valueOf(page))
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
        assertThat(result.getResponse().getStatus()).as("受信申請APIのHTTP 200契約").isEqualTo(200);
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
