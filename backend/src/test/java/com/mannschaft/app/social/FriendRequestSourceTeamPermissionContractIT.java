package com.mannschaft.app.social;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.TeamRolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.service.RolePermissionCacheGenerationService;
import com.mannschaft.app.role.service.RoleService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** CMP-261024-2051: 専用SQLと既存実効権限resolverの同値をfixture変更ごとに比較する。 */
class FriendRequestSourceTeamPermissionContractIT extends FriendRequestContractFixture {
    @Autowired private RoleService roleService;
    @Autowired private RolePermissionCacheGenerationService generations;

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN_DEFAULT", "ADMIN_CEILING_ONLY", "ADMIN_GROUP_ONLY", "DEPUTY_DEFAULT_ONLY", "DEPUTY_GROUP", "MEMBERSHIP_MEMBER_GROUP", "MEMBER_DEFAULT", "MEMBER_GROUP_BLOCKS_DEFAULT", "MEMBER_MULTI_GROUP", "MEMBER_OVERRIDE_ENABLE", "MEMBER_OVERRIDE_DISABLE", "MEMBER_OVERRIDE_OUTSIDE_CEILING", "GROUP_WRONG_TEAM", "GROUP_WRONG_ROLE", "GROUP_DELETED", "SCOPED_ADMIN_STRONGER", "SCOPED_DEPUTY_STRONGER", "SCOPED_GUEST_WEAKER", "GLOBAL_SYSTEM_ADMIN", "GLOBAL_SYSTEM_WITH_ADMIN", "SCOPED_SYSTEM_ADMIN"})
    void FR04_実効権限を既存resolverと同値に解決する(String scenario) throws Exception {
        boolean isAdmin = scenario.startsWith("ADMIN_") || scenario.equals("GLOBAL_SYSTEM_WITH_ADMIN");
        boolean isDeputy = scenario.startsWith("DEPUTY_");
        String name = isAdmin ? "ADMIN" : isDeputy ? "DEPUTY_ADMIN" : "MEMBER";
        Long user = actor(name, own.getId(), false);
        // fixture変更の都度、正規世代更新を通して比較する。warm剥奪試験は別クラスで無効化しない。
        if (scenario.equals("ADMIN_CEILING_ONLY") || scenario.equals("ADMIN_GROUP_ONLY")) {
            em.createQuery("DELETE FROM RolePermissionEntity rp WHERE rp.roleId = :r")
                .setParameter("r", role("ADMIN").getId()).executeUpdate();
            grantDefault("ADMIN", false);
        }
        if (scenario.equals("DEPUTY_DEFAULT_ONLY")) grantDefault("DEPUTY_ADMIN", true);
        if (List.of("MEMBER_DEFAULT", "MEMBER_GROUP_BLOCKS_DEFAULT", "MEMBER_OVERRIDE_DISABLE").contains(scenario)) grantDefault("MEMBER", true);
        if (scenario.equals("MEMBER_OVERRIDE_ENABLE")) { grantDefault("MEMBER", false); override("MEMBER", true); }
        if (scenario.equals("MEMBER_OVERRIDE_DISABLE")) override("MEMBER", false);
        if (scenario.equals("MEMBER_OVERRIDE_OUTSIDE_CEILING")) override("MEMBER", true);
        if (scenario.equals("MEMBERSHIP_MEMBER_GROUP")) {
            em.createQuery("DELETE FROM UserRoleEntity ur WHERE ur.userId = :u").setParameter("u", user).executeUpdate();
        }
        if (scenario.contains("GROUP") || scenario.equals("MEMBERSHIP_MEMBER_GROUP")) {
            boolean wrongTeam = scenario.equals("GROUP_WRONG_TEAM");
            boolean wrongRole = scenario.equals("GROUP_WRONG_ROLE");
            boolean hasPermission = !scenario.equals("MEMBER_GROUP_BLOCKS_DEFAULT");
            var group = assignGroup(user, wrongTeam ? target.getId() : own.getId(),
                wrongRole || isDeputy ? PermissionGroupEntity.TargetRole.DEPUTY_ADMIN : PermissionGroupEntity.TargetRole.MEMBER,
                hasPermission);
            if (scenario.equals("GROUP_DELETED")) org.springframework.test.util.ReflectionTestUtils.setField(group, "deletedAt", java.time.LocalDateTime.now());
            if (scenario.equals("MEMBER_MULTI_GROUP")) assignGroup(user, own.getId(), PermissionGroupEntity.TargetRole.MEMBER, false);
        }
        if (scenario.startsWith("SCOPED_")) {
            em.createQuery("DELETE FROM UserRoleEntity ur WHERE ur.userId = :u").setParameter("u", user).executeUpdate(); em.flush();
            String stronger = scenario.equals("SCOPED_ADMIN_STRONGER") ? "ADMIN" : scenario.equals("SCOPED_DEPUTY_STRONGER") ? "DEPUTY_ADMIN" : scenario.equals("SCOPED_SYSTEM_ADMIN") ? "SYSTEM_ADMIN" : "GUEST";
            userRoles.save(UserRoleEntity.builder().userId(user).roleId(role(stronger).getId()).teamId(own.getId()).build());
            if (scenario.equals("SCOPED_DEPUTY_STRONGER")) assignGroup(user, own.getId(), PermissionGroupEntity.TargetRole.DEPUTY_ADMIN, true);
            if (scenario.equals("SCOPED_GUEST_WEAKER")) assignGroup(user, own.getId(), PermissionGroupEntity.TargetRole.MEMBER, true);
        }
        if (scenario.startsWith("GLOBAL_SYSTEM")) {
            if (scenario.equals("GLOBAL_SYSTEM_ADMIN")) {
                em.createQuery("DELETE FROM UserRoleEntity ur WHERE ur.userId = :u").setParameter("u", user).executeUpdate();
            }
            userRoles.save(UserRoleEntity.builder().userId(user).roleId(role("SYSTEM_ADMIN").getId()).build()); systemActors.add(user);
        }
        em.flush(); em.clear(); generations.incrementGeneration("TEAM", own.getId());
        boolean expected = roleService.hasPermission(user, own.getId(), "TEAM", PERMISSION);
        assertThat(expected).as("resolver自身の仕様: " + scenario).isEqualTo(List.of("ADMIN_DEFAULT", "DEPUTY_GROUP", "MEMBERSHIP_MEMBER_GROUP", "MEMBER_DEFAULT", "MEMBER_MULTI_GROUP", "MEMBER_OVERRIDE_ENABLE", "SCOPED_ADMIN_STRONGER", "SCOPED_DEPUTY_STRONGER", "SCOPED_GUEST_WEAKER", "GLOBAL_SYSTEM_WITH_ADMIN").contains(scenario));
        var response = ok(auth(get("/api/v1/me/friend-request-source-teams").param("targetTeamSlug", target.getSlug()), user));
        assertThat(response.path("data").size()).as(scenario + " resolver=" + expected).isEqualTo(expected ? 1 : 0);
        assertThat(response.path("pagination").path("totalElements").asLong()).isEqualTo(expected ? 1 : 0);
    }

    private void grantDefault(String name, boolean enabled) {
        rolePermissions.save(RolePermissionEntity.builder().roleId(role(name).getId()).permissionId(permission.getId()).isDefault(enabled).build());
    }

    private void override(String name, boolean enabled) {
        em.persist(TeamRolePermissionEntity.builder().scopeType("TEAM").scopeId(own.getId()).roleId(role(name).getId())
            .permissionId(permission.getId()).isEnabled(enabled).createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }

    private PermissionGroupEntity assignGroup(Long user, Long team, PermissionGroupEntity.TargetRole targetRole, boolean grant) {
        var group = groups.save(PermissionGroupEntity.builder().teamId(team).name("同値" + unique()).targetRole(targetRole).build());
        userGroups.save(UserPermissionGroupEntity.builder().userId(user).groupId(group.getId()).build());
        if (grant) groupPermissions.save(PermissionGroupPermissionEntity.builder().groupId(group.getId()).permissionId(permission.getId()).build());
        return group;
    }
}
