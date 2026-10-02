package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.RolePermissionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OSIV=falseかつテスト外側TXなしで、実際のController serialize境界を検証する。 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ProxyConsentManagementSerializationContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ProxyInputConsentRepository consents;
    @Autowired private ProxyInputRecordRepository records;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private RolePermissionRepository rolePermissions;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> consentIds = new ArrayList<>();
    private Long organization;
    private Long admin;
    private Long proxy;
    private Long pending;
    private Long createdRole;
    private Long createdPermission;
    private Long createdRolePermission;

    @BeforeEach
    void fixtureのみを別TXで確定() {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            var fixture = new ProxyConsentManagementTestFixture(users, organizations, consents, records);
            organization = fixture.organization();
            admin = account(fixture);
            proxy = account(fixture);
            boolean hadRole = roles.findByName("ADMIN").isPresent();
            MembershipTestHelper.insertUserRole(em, admin, "ADMIN", null, organization);
            var role = roles.findByName("ADMIN").orElseThrow();
            if (!hadRole) createdRole = role.getId();
            var existing = permissions.findByNameIn(List.of("PROXY_CONSENT_APPROVE"));
            var permission = existing.isEmpty()
                    ? permissions.save(PermissionEntity.builder().name("PROXY_CONSENT_APPROVE")
                            .displayName("代理同意承認").scope(PermissionEntity.Scope.ORGANIZATION).build())
                    : existing.getFirst();
            if (existing.isEmpty()) createdPermission = permission.getId();
            if (rolePermissions.findByRoleId(role.getId()).stream()
                    .noneMatch(value -> value.getPermissionId().equals(permission.getId()))) {
                createdRolePermission = rolePermissions.save(RolePermissionEntity.builder()
                        .roleId(role.getId()).permissionId(permission.getId()).isDefault(true).build()).getId();
            }
            for (int index = 0; index < 10; index++) {
                var consent = fixture.consent(organization, account(fixture), proxy);
                consent.getScopes().add(ProxyInputConsentScopeEntity.create(
                        ProxyInputConsentScopeEntity.FeatureScope.SURVEY));
                if (index == 0) consent.approve(admin);
                consents.save(consent);
                consentIds.add(consent.getId());
                if (index == 1) pending = consent.getId();
            }
        });
    }

    @AfterEach
    void 自分が作ったfixtureだけを後始末() {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            consents.deleteAllById(consentIds);
            consents.flush();
            if (!userIds.isEmpty()) {
                em.createQuery("delete UserRoleEntity role where role.userId in :users")
                        .setParameter("users", userIds).executeUpdate();
                users.deleteAllById(userIds);
            }
            if (organization != null) organizations.deleteById(organization);
            if (createdRolePermission != null) rolePermissions.deleteById(createdRolePermission);
            if (createdPermission != null) permissions.deleteById(createdPermission);
            if (createdRole != null) roles.deleteById(createdRole);
        });
    }

    @Test
    void 同意一覧と有効一覧を実TX外でserializeしscopeのNプラス1を起こさない() throws Exception {
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            mvc.perform(get("/api/v1/organizations/{org}/proxy-input-consents", organization)
                            .with(user(admin.toString())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(10))
                    .andExpect(jsonPath("$.data[0].scopes[0]").value("SURVEY"));
            assertThat(statistics.getCollectionStatistics(
                    "com.mannschaft.app.proxy.entity.ProxyInputConsentEntity.scopes").getFetchCount()).isZero();
            mvc.perform(get("/api/v1/proxy-input-consents/active").with(user(proxy.toString())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].scopes[0]").value("SURVEY"));
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void 承認応答のscopeも実TX外でserializeできる() throws Exception {
        mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", pending)
                        .with(user(admin.toString())).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.scopes[0]").value("SURVEY"));
    }

    private Long account(ProxyConsentManagementTestFixture fixture) {
        Long id = fixture.account();
        userIds.add(id);
        return id;
    }
}
