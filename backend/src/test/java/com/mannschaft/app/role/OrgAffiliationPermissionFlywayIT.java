package com.mannschaft.app.role;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 5-A — 権限 {@code MANAGE_ORG_AFFILIATION} の Flyway 移行後の状態を検証する（試練・実装前 red）。
 *
 * <p>正本は {@code docs/features/F01.2.1_org_team_groups.md} §3.2・§12（8本目）と AC-P01・AC-P09。
 * Flyway を実際に流した DB（ddl-auto=none）で検証するため、金型
 * {@link TimelineDeliveryPermissionFlywayIT} と同じく専用の MySQL コンテナを持つ。</p>
 *
 * <h2>red の理由</h2>
 * <p>実装前は {@code permissions} に {@code MANAGE_ORG_AFFILIATION} 行が無く、ADMIN の
 * {@code role_permissions}（is_default=1）も無いため、チーム ADMIN の {@code me/permissions} に現れない。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
@EnabledIf("com.mannschaft.app.role.OrgAffiliationPermissionFlywayIT#isDockerAvailable")
@DisplayName("F01.2.1 5-A MANAGE_ORG_AFFILIATION Flyway 移行後の状態（試練・実装前 red）")
class OrgAffiliationPermissionFlywayIT {

    private static final String MANAGE_ORG_AFFILIATION = "MANAGE_ORG_AFFILIATION";

    private static final Long TA = 930529101L;
    private static final Long TD = 930529102L;
    private static final Long TM = 930529103L;

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_f0121_affiliation_permission_flyway")
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
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

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
    private MockMvc mockMvc;

    @Autowired
    private TeamRepository teamRepository;

    @Test
    @DisplayName("AC-P01(DB): permissions に scope=TEAM で登録され、role_permissions は ADMIN の is_default=1 だけ（DEPUTY・MEMBER の天井行なし）")
    void permissionRegisteredForAdminOnly() {
        @SuppressWarnings("unchecked")
        List<Object[]> permissionRows = em.createNativeQuery(
                        "SELECT name, scope FROM permissions WHERE name = :name")
                .setParameter("name", MANAGE_ORG_AFFILIATION)
                .getResultList();
        assertThat(permissionRows).hasSize(1);
        assertThat((String) permissionRows.get(0)[1]).isEqualTo("TEAM");

        @SuppressWarnings("unchecked")
        List<Object[]> rolePermissionRows = em.createNativeQuery(
                        "SELECT r.name, rp.is_default FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE p.name = :name ORDER BY r.name")
                .setParameter("name", MANAGE_ORG_AFFILIATION)
                .getResultList();
        assertThat(rolePermissionRows).hasSize(1);
        assertThat((String) rolePermissionRows.get(0)[0]).isEqualTo("ADMIN");
        assertThat(toBool(rolePermissionRows.get(0)[1])).isTrue();
    }

    @Test
    @DisplayName("AC-P01(API): 移行後、チーム ADMIN の me/permissions に含まれ、DEPUTY_ADMIN・MEMBER には含まれない")
    void adminHasPermissionButDeputyAndMemberDoNot() throws Exception {
        TeamEntity team = seedTeam();

        mockMvc.perform(get("/api/v1/teams/{slug}/me/permissions", team.getSlug()).with(user(TA.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", hasItem(MANAGE_ORG_AFFILIATION)));
        mockMvc.perform(get("/api/v1/teams/{slug}/me/permissions", team.getSlug()).with(user(TD.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", not(hasItem(MANAGE_ORG_AFFILIATION))));
        mockMvc.perform(get("/api/v1/teams/{slug}/me/permissions", team.getSlug()).with(user(TM.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", not(hasItem(MANAGE_ORG_AFFILIATION))));
    }

    @Test
    @DisplayName("AC-P09: /admin/member-permissions（MEMBER 既定3権限の画面）に MANAGE_ORG_AFFILIATION は出ない")
    void memberDefaultPermissionScreenExcludesAffiliation() throws Exception {
        TeamEntity team = seedTeam();

        mockMvc.perform(get("/api/v1/admin/member-permissions")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(team.getId()))
                        .with(user(TA.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", hasSize(3)))
                .andExpect(jsonPath("$.data.permissions[*].name", not(hasItem(MANAGE_ORG_AFFILIATION))));
    }

    /** チーム T と TA（ADMIN）・TD（DEPUTY_ADMIN）・TM（MEMBER）を用意する。 */
    private TeamEntity seedTeam() {
        TeamEntity team = teamRepository.save(TeamEntity.builder()
                .slug("aff-perm-flyway-" + System.nanoTime())
                .name("加盟操作権限 Flyway 試練チーム")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build());
        for (Long userId : List.of(TA, TD, TM)) {
            MembershipTestHelper.insertActiveUser(em, userId);
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, team.getId(), RoleKind.MEMBER);
        }
        MembershipTestHelper.insertUserRole(em, TA, "ADMIN", team.getId(), null);
        MembershipTestHelper.insertUserRole(em, TD, "DEPUTY_ADMIN", team.getId(), null);
        em.flush();
        em.clear();
        return team;
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        throw new IllegalStateException("Unexpected boolean value: " + value);
    }
}
