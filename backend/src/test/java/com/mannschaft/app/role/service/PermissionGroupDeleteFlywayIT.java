package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Issue #3311: 権限グループ削除時の外部キー制約を実 Flyway スキーマで検証する。
 *
 * <p>通常の統合テストは {@code ddl-auto=create} のため、ID 参照だけを持つ中間 Entity から
 * 外部キーが生成されない。本番と同じ Flyway スキーマで、子行を持つグループの削除が
 * 500 相当の制約違反にならないことを固定する。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@Testcontainers
@Transactional
@EnabledIf("com.mannschaft.app.role.service.PermissionGroupDeleteFlywayIT#isDockerAvailable")
@DisplayName("Issue #3311: 権限グループ削除の外部キー整合性（Flyway 実スキーマ）")
class PermissionGroupDeleteFlywayIT {

    private static final String PERMISSION_NAME = "VIEW_ATTENDANCE";

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_permission_group_delete_flyway")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /** 削除順序の検証に認可判定を混在させないため、入口の認可基盤だけを許可スタブにする。 */
    @MockitoBean
    private AccessControlService accessControlService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PermissionGroupService permissionGroupService;

    @Test
    @DisplayName("子なし・片方のみ・両方の全構成を削除でき、他グループは不変")
    void deletePermissionGroup_全子構成で削除でき他グループは不変() {
        Long organizationId = persistOrganization();
        Long actorUserId = persistUser("actor");
        Long assignedUserId = persistUser("assigned");
        Long permissionId = findPermissionId(PERMISSION_NAME);

        Long withoutChildren = persistGroup(organizationId, "子なし");
        Long permissionOnly = persistGroup(organizationId, "権限のみ");
        addPermission(permissionOnly, permissionId);
        Long assignmentOnly = persistGroup(organizationId, "割当のみ");
        assignUser(assignmentOnly, assignedUserId);
        Long bothChildren = persistGroup(organizationId, "両方");
        addPermission(bothChildren, permissionId);
        assignUser(bothChildren, assignedUserId);

        Long untouched = persistGroup(organizationId, "非対象");
        addPermission(untouched, permissionId);
        assignUser(untouched, assignedUserId);
        em.flush();
        em.clear();

        for (Long groupId : new Long[]{withoutChildren, permissionOnly, assignmentOnly, bothChildren}) {
            assertThatCode(() -> permissionGroupService.deletePermissionGroup(groupId, actorUserId))
                    .as("子レコード構成にかかわらず削除が成功すること: groupId=%s", groupId)
                    .doesNotThrowAnyException();
            em.flush();
            em.clear();
            assertGroupAbsent(groupId);
        }

        assertThat(count("permission_groups", untouched)).isEqualTo(1L);
        assertThat(count("permission_group_permissions", untouched)).isEqualTo(1L);
        assertThat(count("user_permission_groups", untouched)).isEqualTo(1L);
    }

    private Long persistOrganization() {
        OrganizationEntity organization = OrganizationEntity.builder()
                .slug("pg-delete-it")
                .name("権限グループ削除テスト組織")
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(organization);
        em.flush();
        return organization.getId();
    }

    private Long persistUser(String suffix) {
        UserEntity user = UserEntity.builder()
                .email("pg-delete-" + suffix + "@example.com")
                .lastName("権限")
                .firstName("削除" + suffix)
                .displayName("権限削除" + suffix)
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        em.flush();
        return user.getId();
    }

    private Long findPermissionId(String permissionName) {
        PermissionEntity permission = em.createQuery(
                        "select p from PermissionEntity p where p.name = :name", PermissionEntity.class)
                .setParameter("name", permissionName)
                .getSingleResult();
        return permission.getId();
    }

    private Long persistGroup(Long organizationId, String name) {
        PermissionGroupEntity group = PermissionGroupEntity.builder()
                .organizationId(organizationId)
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                .name(name)
                .build();
        em.persist(group);
        em.flush();
        return group.getId();
    }

    private void addPermission(Long groupId, Long permissionId) {
        em.persist(PermissionGroupPermissionEntity.builder()
                .groupId(groupId)
                .permissionId(permissionId)
                .build());
    }

    private void assignUser(Long groupId, Long userId) {
        em.persist(UserPermissionGroupEntity.builder()
                .groupId(groupId)
                .userId(userId)
                .build());
    }

    private void assertGroupAbsent(Long groupId) {
        assertThat(count("permission_groups", groupId)).isZero();
        assertThat(count("permission_group_permissions", groupId)).isZero();
        assertThat(count("user_permission_groups", groupId)).isZero();
    }

    private long count(String table, Long groupId) {
        String groupColumn = "permission_groups".equals(table) ? "id" : "group_id";
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + groupColumn + " = :groupId")
                .setParameter("groupId", groupId)
                .getSingleResult()).longValue();
    }
}
