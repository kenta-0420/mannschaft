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

/** 実JWTとMySQLを通すフレンド申請契約の所有fixture。 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")

abstract class FriendRequestContractFixture extends AbstractMySqlIntegrationTest {

    protected static final String PERMISSION = "MANAGE_FRIEND_TEAMS";
    protected static final String BASE = "/api/v1/teams/{id}/friends";
    protected static final LocalDateTime SAME_SECOND = LocalDateTime.of(2026, 1, 1, 12, 0);
    protected static final long SYNTHETIC_TARGET_START = 10_000_000L;

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected AuthTokenService tokens;
    @Autowired protected UserRepository users;
    @Autowired protected TeamRepository teams;
    @Autowired protected RoleRepository roles;
    @Autowired protected UserRoleRepository userRoles;
    @Autowired protected MembershipRepository memberships;
    @Autowired protected PermissionRepository permissions;
    @Autowired protected RolePermissionRepository rolePermissions;
    @Autowired protected PermissionGroupRepository groups;
    @Autowired protected PermissionGroupPermissionRepository groupPermissions;
    @Autowired protected UserPermissionGroupRepository userGroups;
    @Autowired protected FollowRepository follows;
    @Autowired protected TeamFriendRepository friends;
    @PersistenceContext protected EntityManager em;

    protected TeamEntity own;
    protected TeamEntity target;
    protected Long admin;
    protected PermissionEntity permission;
    protected final java.util.Set<Long> systemActors = new HashSet<>();

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

    protected JsonNode pending(Long actor, TeamEntity source, int page, int size) throws Exception {
        return ok(auth(get(BASE + "/pending", source.getSlug()).param("page", String.valueOf(page))
                .param("size", String.valueOf(size)), actor));
    }

    protected JsonNode follow(Long actor, TeamEntity source, TeamEntity destination) throws Exception {
        MvcResult result = sendFollow(actor, source, destination, false);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json(result);
    }

    protected MvcResult sendFollow(Long actor, TeamEntity source, TeamEntity destination, boolean slug) throws Exception {
        Map<String, Object> body = slug ? Map.of("targetTeamSlug", destination.getSlug())
                : Map.of("targetTeamId", destination.getId());
        return mvc.perform(auth(post(BASE + "/follow", source.getSlug())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)), actor)).andReturn();
    }

    protected void unfollow(Long actor, TeamEntity source, TeamEntity destination) throws Exception {
        assertStatus(auth(delete(BASE + "/follow/{target}", source.getSlug(), destination.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"pastForwardHandling\":\"KEEP\"}"), actor), 204);
    }

    protected JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as("申請APIのHTTP 200契約").isEqualTo(200);
        return json(result);
    }

    protected void assertStatus(MockHttpServletRequestBuilder request, int expected) throws Exception {
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(expected);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, Long actor) {
        return request.header("Accept-Language", "ja").header("Authorization", "Bearer "
                + tokens.issueAccessToken(actor, systemActors.contains(actor)
                        ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER")));
    }

    protected TeamEntity team() {
        return teams.save(TeamEntity.builder().slug("pending-" + unique()).name("フォロー契約" + unique())
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build());
    }

    protected RoleEntity role(String name) {
        return roles.findByName(name).orElseGet(() -> roles.save(RoleEntity.builder().name(name).displayName(name)
                .priority(RolePriority.priority(name)).isSystem(name.equals("SYSTEM_ADMIN")).build()));
    }

    protected Long actor(String name, Long teamId, boolean group) {
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

    protected Long authorizedActor(String label) {
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

    protected Long unauthorizedActor(String label) {
        if (label.equals("OUTSIDER")) { return actor(null, null, false); }
        if (label.equals("OTHER_ADMIN")) { return actor("ADMIN", team().getId(), false); }
        return actor(label, label.equals("SYSTEM_ADMIN") ? null : own.getId(), false);
    }

    protected void membership(Long actor, Long teamId, RoleKind kind) {
        memberships.save(MembershipEntity.builder().userId(actor).scopeType(ScopeType.TEAM).scopeId(teamId)
                .roleKind(kind).joinedAt(LocalDateTime.now()).build());
    }

    protected FollowEntity followRow(FollowerType fromType, Long from, FollowerType toType, Long to) {
        return follows.save(FollowEntity.builder().followerType(fromType).followerId(from)
                .followedType(toType).followedId(to).build());
    }

    protected String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
