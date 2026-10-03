package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
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
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void 同意ページングはcollectionのメモリ内制限を使わず取得entity数を制限() throws Exception {
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            mvc.perform(get("/api/v1/organizations/{org}/proxy-input-consents", organization)
                            .with(user(admin.toString())).param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].scopes[0]").value("SURVEY"))
                    .andExpect(jsonPath("$.meta.total").value(10));
            String entity = "com.mannschaft.app.proxy.entity.ProxyInputConsentEntity";
            assertThat(statistics.getEntityStatistics(entity).getLoadCount()).isEqualTo(1);
            long smallSqlCount = statistics.getPrepareStatementCount();
            statistics.clear();
            mvc.perform(get("/api/v1/organizations/{org}/proxy-input-consents", organization)
                            .with(user(admin.toString())).param("size", "10"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(10));
            assertThat(statistics.getEntityStatistics(entity).getLoadCount()).isEqualTo(10);
            assertThat(statistics.getCollectionStatistics(entity + ".scopes").getFetchCount()).isZero();
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(smallSqlCount + 1);
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    private Long account(ProxyConsentManagementTestFixture fixture) {
        Long id = fixture.account();
        userIds.add(id);
        return id;
    }

    @Test
    void 先行撤回と競合する承認は確定後409で承認情報を追加しない() throws Exception {
        撤回と後続要求を競合させる(true);
    }

    @Test
    void 先行撤回と競合する再撤回は確定後409で撤回情報を上書きしない() throws Exception {
        撤回と後続要求を競合させる(false);
    }

    private void 撤回と後続要求を競合させる(boolean approval) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requestThread = new AtomicReference<Thread>();
        var firstRevokedAt = new AtomicReference<java.time.LocalDateTime>();
        try {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(ignored -> {
                        var consent = em.find(ProxyInputConsentEntity.class, pending, LockModeType.PESSIMISTIC_WRITE);
                        consent.revoke(ProxyInputConsentEntity.RevokeMethod.PAPER_BY_SUBJECT, admin, "先行撤回");
                        em.flush();
                        em.refresh(consent);
                        firstRevokedAt.set(consent.getRevokedAt());
                        locked.countDown();
                        try {
                            assertThat(release.await(30, TimeUnit.SECONDS)).as("先行TXの解放期限").isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("先行TXのrow lock取得").isTrue();
            var request = executor.submit(() -> {
                requestThread.set(Thread.currentThread());
                var builder = patch("/api/v1/proxy-input-consents/{id}/" + (approval ? "approve" : "revoke"), pending)
                        .with(user(admin.toString())).with(csrf());
                if (!approval) builder.contentType(MediaType.APPLICATION_JSON).content(
                        "{\"revokeMethod\":\"PAPER_BY_SUBJECT\",\"revokeWitnessedByUserId\":" + admin
                                + ",\"revokeReason\":\"後続撤回\"}");
                return mvc.perform(builder).andReturn();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (!request.isDone() && System.nanoTime() < deadline) {
                Thread thread = requestThread.get();
                if (thread != null) {
                    var stack = Arrays.asList(thread.getStackTrace());
                    boolean mutationBoundary = stack.stream().anyMatch(frame ->
                            frame.getMethodName().equals("findByIdForUpdate")
                                    || frame.getClassName().endsWith("DefaultFlushEventListener"));
                    boolean databaseRead = stack.stream().anyMatch(frame ->
                            frame.getClassName().equals("com.mysql.cj.protocol.a.NativeProtocol")
                                    && frame.getMethodName().equals("readMessage"));
                    if (mutationBoundary && databaseRead) {
                        waiting = true;
                        break;
                    }
                }
                Thread.yield();
            }
            assertThat(waiting).as("後続HTTPが先行TXのrow lockをDBで待つ").isTrue();
            release.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertThat(request.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
            new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
                var consent = consents.findById(pending).orElseThrow();
                assertThat(consent.getApprovedAt()).isNull();
                assertThat(consent.getRevokeReason()).isEqualTo("先行撤回");
                assertThat(consent.getRevokeWitnessedByUserId()).isEqualTo(admin);
                assertThat(consent.getRevokedAt()).isEqualTo(firstRevokedAt.get());
            });
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("自所有workerの終了").isTrue();
        }
    }
}
