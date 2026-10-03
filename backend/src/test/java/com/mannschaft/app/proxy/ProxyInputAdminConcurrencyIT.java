package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理APIのトランザクション境界・互換経路・並行競合を実DBとHTTPで検証する。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("代理入力管理API 追加統合試験")
class ProxyInputAdminConcurrencyIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProxyInputConsentRepository consentRepository;

    @Autowired
    private ProxyInputRecordRepository recordRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate transactionTemplate;
    private Long organizationId;
    private Long adminId;
    private Long systemAdminId;
    private Long subjectId;
    private Long proxyId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            organizationId = insertOrganization();
            adminId = insertUser("admin");
            systemAdminId = insertUser("system");
            subjectId = insertUser("subject");
            proxyId = insertUser("proxy");
            outsiderId = insertUser("outsider");
            MembershipTestHelper.insertMembership(
                    em, adminId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", null, organizationId);
            MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);
            em.flush();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (transactionTemplate == null || organizationId == null) {
            return;
        }
        Throwable failure = null;
        try {
            awaitPersistedConsentAudits();
        } catch (Throwable auditFailure) {
            failure = auditFailure;
        }
        try {
            cleanupFixture();
        } catch (Throwable cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new AssertionError(failure);
        }
    }

    private void awaitPersistedConsentAudits() {
        List<?> mutations = transactionTemplate.execute(status -> em.createNativeQuery("""
                        SELECT id, approved_at, revoked_at
                        FROM proxy_input_consents
                        WHERE organization_id = :organizationId
                          AND (approved_at IS NOT NULL OR revoked_at IS NOT NULL)
                        """)
                .setParameter("organizationId", organizationId)
                .getResultList());
        if (mutations == null) {
            return;
        }
        for (Object mutationRow : mutations) {
            Object[] mutation = (Object[]) mutationRow;
            Long consentId = ((Number) mutation[0]).longValue();
            if (mutation[1] != null) {
                assertSingleAuditEvent(ProxyAuditEventTypes.PROXY_CONSENT_APPROVED, consentId);
            }
            if (mutation[2] != null) {
                assertSingleAuditEvent(ProxyAuditEventTypes.PROXY_CONSENT_REVOKED, consentId);
            }
        }
    }

    private void cleanupFixture() {
        transactionTemplate.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM proxy_input_records WHERE proxy_input_consent_id IN "
                            + "(SELECT id FROM proxy_input_consents WHERE organization_id = :organizationId) "
                            + "OR subject_user_id IN (:subjectId, :outsiderId)")
                    .setParameter("organizationId", organizationId)
                    .setParameter("subjectId", subjectId)
                    .setParameter("outsiderId", outsiderId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM proxy_input_consent_scopes WHERE proxy_input_consent_id IN "
                            + "(SELECT id FROM proxy_input_consents WHERE organization_id = :organizationId)")
                    .setParameter("organizationId", organizationId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM proxy_input_consents WHERE organization_id = :organizationId")
                    .setParameter("organizationId", organizationId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM audit_logs WHERE organization_id = :organizationId")
                    .setParameter("organizationId", organizationId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN "
                            + "(:adminId, :systemAdminId, :subjectId, :proxyId, :outsiderId)")
                    .setParameter("adminId", adminId)
                    .setParameter("systemAdminId", systemAdminId)
                    .setParameter("subjectId", subjectId)
                    .setParameter("proxyId", proxyId)
                    .setParameter("outsiderId", outsiderId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE user_id = :adminId")
                    .setParameter("adminId", adminId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id IN "
                            + "(:adminId, :systemAdminId, :subjectId, :proxyId, :outsiderId)")
                    .setParameter("adminId", adminId)
                    .setParameter("systemAdminId", systemAdminId)
                    .setParameter("subjectId", subjectId)
                    .setParameter("proxyId", proxyId)
                    .setParameter("outsiderId", outsiderId)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM organizations WHERE id = :organizationId")
                    .setParameter("organizationId", organizationId)
                    .executeUpdate();
        });
    }

    @Test
    @DisplayName("同じ同意書への並行承認・撤回は各1件だけ成功する")
    void concurrentMutationsAreSerialized() throws Exception {
        Long approveTargetId = saveConsent(subjectId, proxyId);
        assertThat(runConcurrently(() -> approveStatus(approveTargetId, systemAdminId)))
                .containsExactlyInAnyOrder(200, 409);
        assertSingleAuditEvent(ProxyAuditEventTypes.PROXY_CONSENT_APPROVED, approveTargetId);

        Long revokeTargetId = saveConsent(subjectId, proxyId);
        assertThat(runConcurrently(() -> revokeStatus(revokeTargetId, subjectId, "API_BY_SUBJECT", null)))
                .containsExactlyInAnyOrder(200, 409);
        assertSingleAuditEvent(ProxyAuditEventTypes.PROXY_CONSENT_REVOKED, revokeTargetId);
    }

    @Test
    @DisplayName("OSIV無効でも承認応答と有効同意一覧のscopesを返せる")
    void responseMappingCompletesInsideTransaction() throws Exception {
        Long consentId = saveConsent(subjectId, proxyId);

        mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId)
                        .with(user(systemAdminId.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopes[0]").value("SURVEY"));
        assertSingleAuditEvent(ProxyAuditEventTypes.PROXY_CONSENT_APPROVED, consentId);

        mockMvc.perform(get("/api/v1/proxy-input-consents/active")
                        .with(user(proxyId.toString()).roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scopes[0]").value("SURVEY"));
    }

    @Test
    @DisplayName("紙撤回はリクエスト指定値でなく認証済み管理者を立会人に記録する")
    void paperRevocationRecordsAuthenticatedActor() throws Exception {
        Long consentId = saveConsent(subjectId, proxyId);

        mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/revoke", consentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revokeMethod\":\"PAPER_BY_SUBJECT\","
                                + "\"revokeWitnessedByUserId\":999999}")
                .with(user(adminId.toString()).roles("MEMBER")))
                .andExpect(status().isOk());
        assertSingleAuditEvent(ProxyAuditEventTypes.PROXY_CONSENT_REVOKED, consentId);

        Long witnessedBy = transactionTemplate.execute(status -> consentRepository.findById(consentId)
                .orElseThrow()
                .getRevokeWitnessedByUserId());
        assertThat(witnessedBy).isEqualTo(adminId);
    }

    @Test
    @DisplayName("組合ID省略時は本人履歴を維持し他人指定を拒否する")
    void subjectRecordPathRemainsAvailable() throws Exception {
        Long consentId = saveConsent(subjectId, proxyId);
        saveRecord(consentId, subjectId, 101L);
        saveRecord(null, subjectId, 102L);

        mockMvc.perform(get("/api/v1/proxy-input-records")
                        .with(user(subjectId.toString()).roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].targetEntityId").value(102L))
                .andExpect(jsonPath("$.data[0].proxyInputConsentId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].auditLogId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].createdAt").value(endsWith("Z")));

        mockMvc.perform(get("/api/v1/proxy-input-records")
                        .param("subjectUserId", subjectId.toString())
                        .with(user(outsiderId.toString()).roles("MEMBER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/proxy-input-records")
                        .param("subjectUserId", subjectId.toString())
                        .with(user(systemAdminId.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("組合履歴は本人絞込と同時刻の安定ページ順を保つ")
    void organizationRecordsUseStablePagingAndSubjectFilter() throws Exception {
        Long consentId = saveConsent(subjectId, proxyId);
        Long firstId = saveRecord(consentId, subjectId, 201L);
        Long secondId = saveRecord(consentId, subjectId, 202L);
        saveRecord(consentId, outsiderId, 203L);
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime sameTime = LocalDateTime.of(2026, 1, 1, 12, 0);
            em.createNativeQuery("UPDATE proxy_input_records SET created_at = :createdAt WHERE id IN (:firstId, :secondId)")
                    .setParameter("createdAt", sameTime)
                    .setParameter("firstId", firstId)
                    .setParameter("secondId", secondId)
                    .executeUpdate();
        });

        assertRecordPage(0, 202L);
        assertRecordPage(1, 201L);
    }

    private void assertRecordPage(int page, Long targetEntityId) throws Exception {
        mockMvc.perform(get("/api/v1/proxy-input-records")
                        .param("organizationId", organizationId.toString())
                        .param("subjectUserId", subjectId.toString())
                        .param("page", Integer.toString(page))
                        .param("size", "1")
                        .with(user(adminId.toString()).roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].targetEntityId").value(targetEntityId))
                .andExpect(jsonPath("$.meta.total").value(2));
    }

    /** 非同期監査を実DBで待ち、各状態遷移につき1行だけ記録されたことを保証する。 */
    private void assertSingleAuditEvent(String eventType, Long consentId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(countAuditEvents(eventType, consentId)).isEqualTo(1L));
    }

    private long countAuditEvents(String eventType, Long consentId) {
        Number count = transactionTemplate.execute(status -> (Number) em.createNativeQuery("""
                        SELECT COUNT(*)
                        FROM audit_logs
                        WHERE organization_id = :organizationId
                          AND event_type = :eventType
                          AND JSON_EXTRACT(metadata, '$.consentId') = :consentId
                        """)
                .setParameter("organizationId", organizationId)
                .setParameter("eventType", eventType)
                .setParameter("consentId", consentId)
                .getSingleResult());
        return count == null ? 0L : count.longValue();
    }

    private List<Integer> runConcurrently(ThrowingStatusCall call) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> awaitAndCall(ready, start, call));
            Future<Integer> second = executor.submit(() -> awaitAndCall(ready, start, call));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private int awaitAndCall(CountDownLatch ready, CountDownLatch start, ThrowingStatusCall call)
            throws Exception {
        ready.countDown();
        assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
        return call.execute();
    }

    private int approveStatus(Long consentId, Long actorId) throws Exception {
        return mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId)
                        .with(user(actorId.toString()).roles("SYSTEM_ADMIN")))
                .andReturn().getResponse().getStatus();
    }

    private int revokeStatus(Long consentId, Long actorId, String method, Long witnessId) throws Exception {
        String witness = witnessId == null ? "null" : witnessId.toString();
        return mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/revoke", consentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revokeMethod\":\"" + method
                                + "\",\"revokeWitnessedByUserId\":" + witness + "}")
                        .with(user(actorId.toString()).roles("MEMBER")))
                .andReturn().getResponse().getStatus();
    }

    private Long saveConsent(Long subjectUserId, Long proxyUserId) {
        return transactionTemplate.execute(status -> {
            ProxyInputConsentEntity consent = ProxyInputConsentEntity.create(
                    subjectUserId,
                    proxyUserId,
                    organizationId,
                    ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                    null,
                    null,
                    null,
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusMonths(6));
            consent.getScopes().add(ProxyInputConsentScopeEntity.create(
                    ProxyInputConsentScopeEntity.FeatureScope.SURVEY));
            return consentRepository.saveAndFlush(consent).getId();
        });
    }

    private Long saveRecord(Long consentId, Long recordSubjectId, Long targetEntityId) {
        return transactionTemplate.execute(status -> recordRepository.saveAndFlush(
                ProxyInputRecordEntity.create(
                        consentId,
                        recordSubjectId,
                        proxyId,
                        "SURVEY",
                        "SURVEY_RESPONSE",
                        targetEntityId,
                        ProxyInputRecordEntity.InputSource.PAPER_FORM,
                        "organization archive")).getId());
    }

    private Long insertUser(String label) {
        UserEntity user = UserEntity.builder()
                .email("cmp1018-" + label + "-" + UUID.randomUUID() + "@example.com")
                .lastName("CMP1018")
                .firstName(label)
                .displayName("CMP1018 " + label)
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        em.flush();
        return user.getId();
    }

    private Long insertOrganization() {
        OrganizationEntity organization = OrganizationEntity.builder()
                .slug("cmp1018-" + UUID.randomUUID().toString().substring(0, 8))
                .name("CMP1018 concurrency")
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(organization);
        em.flush();
        return organization.getId();
    }

    @FunctionalInterface
    private interface ThrowingStatusCall {
        int execute() throws Exception;
    }
}
