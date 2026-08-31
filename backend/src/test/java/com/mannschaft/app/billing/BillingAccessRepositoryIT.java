package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 課金管理の認可 query は role_permissions や RoleService のキャッシュを流用してはならない。
 * 実 MySQL で user_roles と permission group の結合スコープを固定する。
 */
@Transactional
@DisplayName("BillingAccessRepository 実 MySQL 認可契約")
class BillingAccessRepositoryIT extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long TEAM_A = 881_001L;
    private static final long TEAM_B = 881_002L;
    private static final long ORGANIZATION_A = 882_001L;
    private static final long ORGANIZATION_B = 882_002L;
    private static final String TEAM_PERMISSION = "MANAGE_TEAM_BILLING";
    private static final String ORGANIZATION_PERMISSION = "MANAGE_ORGANIZATION_BILLING";

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private BillingAccessRepository billingAccessRepository;

    @Test
    @DisplayName("同一 scope の ADMIN は課金管理者として存在する")
    void sameScope_admin_isAllowed() {
        Long admin = user();
        grantRole(admin, "ADMIN", TEAM_A);
        flushClear();

        assertThat(billingAccessRepository.existsAdmin(admin, EntitlementScopeKind.TEAM, TEAM_A)).isTrue();
    }

    @Test
    @DisplayName("同一 scope の ORGANIZATION ADMIN は課金管理者として存在する")
    void sameOrganization_admin_isAllowed() {
        Long admin = user();
        grantOrganizationRole(admin, "ADMIN", ORGANIZATION_A);
        flushClear();

        assertThat(billingAccessRepository.existsAdmin(
                admin, EntitlementScopeKind.ORGANIZATION, ORGANIZATION_A)).isTrue();
        assertThat(billingAccessRepository.existsAdmin(
                admin, EntitlementScopeKind.ORGANIZATION, ORGANIZATION_B)).isFalse();
    }

    @Test
    @DisplayName("DEPUTY_ADMIN は同一 TEAM permission group の明示付与でのみ許可される")
    void deputy_explicitPermissionGroup_isAllowed() {
        Long deputy = user();
        grantRole(deputy, "DEPUTY_ADMIN", TEAM_A);
        Long permission = permission(TEAM_PERMISSION, PermissionEntity.Scope.TEAM);
        Long group = group(TEAM_A, PermissionGroupEntity.TargetRole.DEPUTY_ADMIN);
        em.persist(PermissionGroupPermissionEntity.builder().groupId(group).permissionId(permission).build());
        em.persist(UserPermissionGroupEntity.builder().userId(deputy).groupId(group).build());
        flushClear();

        assertThat(billingAccessRepository.existsDeputyPermissionGroup(
                deputy, EntitlementScopeKind.TEAM, TEAM_A, TEAM_PERMISSION)).isTrue();
    }

    @Test
    @DisplayName("ORGANIZATION の DEPUTY_ADMIN は同一組織 permission group の明示付与時だけ許可する")
    void organizationDeputy_explicitPermissionGroup_isAllowed() {
        Long deputy = user();
        grantOrganizationRole(deputy, "DEPUTY_ADMIN", ORGANIZATION_A);
        Long permission = permission(ORGANIZATION_PERMISSION, PermissionEntity.Scope.ORGANIZATION);
        Long group = organizationGroup(ORGANIZATION_A, PermissionGroupEntity.TargetRole.DEPUTY_ADMIN);
        em.persist(PermissionGroupPermissionEntity.builder().groupId(group).permissionId(permission).build());
        em.persist(UserPermissionGroupEntity.builder().userId(deputy).groupId(group).build());
        flushClear();

        assertThat(billingAccessRepository.existsDeputyPermissionGroup(
                deputy, EntitlementScopeKind.ORGANIZATION,
                ORGANIZATION_A, ORGANIZATION_PERMISSION)).isTrue();
        assertThat(billingAccessRepository.existsDeputyPermissionGroup(
                deputy, EntitlementScopeKind.ORGANIZATION,
                ORGANIZATION_B, ORGANIZATION_PERMISSION)).isFalse();
    }

    @Test
    @DisplayName("DEPUTY_ADMIN の role_permissions だけでは許可しない")
    void deputy_rolePermissionOnly_isDenied() {
        Long deputy = user();
        Long role = role("DEPUTY_ADMIN");
        Long permission = permission(TEAM_PERMISSION, PermissionEntity.Scope.TEAM);
        em.persist(UserRoleEntity.builder().userId(deputy).roleId(role).teamId(TEAM_A).build());
        em.persist(RolePermissionEntity.builder().roleId(role).permissionId(permission).isDefault(true).build());
        flushClear();

        assertThat(billingAccessRepository.existsDeputyPermissionGroup(
                deputy, EntitlementScopeKind.TEAM, TEAM_A, TEAM_PERMISSION)).isFalse();
    }

    @Test
    @DisplayName("MEMBER は permission group を割り当てても許可しない")
    void member_isDenied() {
        Long member = user();
        grantRole(member, "MEMBER", TEAM_A);
        Long permission = permission(TEAM_PERMISSION, PermissionEntity.Scope.TEAM);
        Long group = group(TEAM_A, PermissionGroupEntity.TargetRole.DEPUTY_ADMIN);
        em.persist(PermissionGroupPermissionEntity.builder().groupId(group).permissionId(permission).build());
        em.persist(UserPermissionGroupEntity.builder().userId(member).groupId(group).build());
        flushClear();

        assertThat(billingAccessRepository.existsDeputyPermissionGroup(
                member, EntitlementScopeKind.TEAM, TEAM_A, TEAM_PERMISSION)).isFalse();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN は consumer billing API の管理者として短絡許可しない")
    void systemAdmin_isDeniedForConsumerAccess() {
        Long systemAdmin = user();
        em.persist(UserRoleEntity.builder()
                .userId(systemAdmin).roleId(role("SYSTEM_ADMIN")).build());
        flushClear();

        assertThat(billingAccessRepository.existsAdmin(
                systemAdmin, EntitlementScopeKind.TEAM, TEAM_A)).isFalse();
    }

    @Test
    @DisplayName("別 scope の permission group は同じ利用者にも効かない")
    void crossScopePermissionGroup_isDenied() {
        Long deputy = user();
        grantRole(deputy, "DEPUTY_ADMIN", TEAM_A);
        Long permission = permission(TEAM_PERMISSION, PermissionEntity.Scope.TEAM);
        Long group = group(TEAM_B, PermissionGroupEntity.TargetRole.DEPUTY_ADMIN);
        em.persist(PermissionGroupPermissionEntity.builder().groupId(group).permissionId(permission).build());
        em.persist(UserPermissionGroupEntity.builder().userId(deputy).groupId(group).build());
        flushClear();

        assertThat(billingAccessRepository.existsDeputyPermissionGroup(
                deputy, EntitlementScopeKind.TEAM, TEAM_A, TEAM_PERMISSION)).isFalse();
    }

    @Test
    @DisplayName("permission group の取消直後は次の query で直ちに拒否する")
    void revokedPermissionGroup_isDeniedImmediately() {
        Long deputy = user();
        grantRole(deputy, "DEPUTY_ADMIN", TEAM_A);
        Long permission = permission(TEAM_PERMISSION, PermissionEntity.Scope.TEAM);
        Long group = group(TEAM_A, PermissionGroupEntity.TargetRole.DEPUTY_ADMIN);
        em.persist(PermissionGroupPermissionEntity.builder().groupId(group).permissionId(permission).build());
        UserPermissionGroupEntity assignment = UserPermissionGroupEntity.builder().userId(deputy).groupId(group).build();
        em.persist(assignment);
        em.flush();
        em.remove(assignment);
        flushClear();

        assertThat(billingAccessRepository.existsDeputyPermissionGroup(
                deputy, EntitlementScopeKind.TEAM, TEAM_A, TEAM_PERMISSION)).isFalse();
    }

    private Long user() {
        int n = SEQ.incrementAndGet();
        UserEntity user = UserEntity.builder().email("billing-access-" + n + "@example.com")
                .lastName("課金").firstName("認可" + n).displayName("課金認可" + n)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .isSearchable(true).build();
        em.persist(user);
        return user.getId();
    }

    private void grantRole(Long userId, String roleName, long teamId) {
        em.persist(UserRoleEntity.builder().userId(userId).roleId(role(roleName)).teamId(teamId).build());
    }

    private void grantOrganizationRole(Long userId, String roleName, long organizationId) {
        em.persist(UserRoleEntity.builder().userId(userId).roleId(role(roleName))
                .organizationId(organizationId).build());
    }

    private Long role(String name) {
        var ids = em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name).getResultList();
        if (!ids.isEmpty()) return ((Number) ids.get(0)).longValue();
        RoleEntity entity = RoleEntity.builder().name(name).displayName(name)
                .priority(1).isSystem(true).build();
        em.persist(entity);
        em.flush();
        return entity.getId();
    }

    private Long permission(String name, PermissionEntity.Scope scope) {
        var ids = em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", name).getResultList();
        if (!ids.isEmpty()) return ((Number) ids.get(0)).longValue();
        PermissionEntity entity = PermissionEntity.builder().name(name).displayName(name).scope(scope).build();
        em.persist(entity);
        em.flush();
        return entity.getId();
    }

    private Long group(long teamId, PermissionGroupEntity.TargetRole targetRole) {
        PermissionGroupEntity entity = PermissionGroupEntity.builder().teamId(teamId).targetRole(targetRole)
                .name("billing-access-" + SEQ.incrementAndGet()).build();
        em.persist(entity);
        em.flush();
        return entity.getId();
    }

    private Long organizationGroup(long organizationId, PermissionGroupEntity.TargetRole targetRole) {
        PermissionGroupEntity entity = PermissionGroupEntity.builder()
                .organizationId(organizationId).targetRole(targetRole)
                .name("billing-access-org-" + SEQ.incrementAndGet()).build();
        em.persist(entity);
        em.flush();
        return entity.getId();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }
}
