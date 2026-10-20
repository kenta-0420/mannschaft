package com.mannschaft.app.ranch;

import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.dto.RanchCareRulePublicationRequest;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsRequest;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationRequest;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchCareRuleRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC72: 実フィルタ・実MySQL・fresh admissionを通す管理入口。
 * MockMvcの認証principalは合成するが、DB資格・ACTIVE・管理handlerはmockしない。
 * JWT署名／ブラウザ／四源health閉束の証明とは別の試験である。
 * AC45の追加試験は、未参加管理者の既存保存契約を固定する回帰防止柵である。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchAdminHttpIT extends AbstractMySqlIntegrationTest {
    private static final String HISTORY = "/api/v1/system-admin/ranch/care-rules";
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;
    @Autowired private RanchAdminCommandRepository commands;
    @Autowired private RanchCareRuleRepository rules;
    @Autowired private RanchRewardPolicyRepository policies;
    @Autowired private RanchOperationalControlRepository controls;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    private TransactionTemplate transaction;
    private Long administrator;
    private Long ordinary;
    private final List<UUID> ownWriteKeys = new ArrayList<>();
    private RanchOperationalControlEntity originalControl;
    private boolean controlFixtureChanged;

    @BeforeEach
    void committedSyntheticQualification() {
        transaction = new TransactionTemplate(transactionManager);
        // admissionのREQUIRES_NEWから見えるよう、HTTP操作前にfixtureだけをcommitする。
        transaction.executeWithoutResult(ignored -> {
            administrator = users.saveAndFlush(RanchTestFixture.user()).getId();
            ordinary = users.saveAndFlush(RanchTestFixture.user()).getId();
            MembershipTestHelper.insertUserRole(em, administrator, "SYSTEM_ADMIN", null, null);
        });
    }

    @AfterEach
    void removeOnlyOwnFixtures() throws Exception {
        if (transaction == null || administrator == null || ordinary == null) return;
        // HTTP応答検証が失敗しても、保存済みACKを正本に本人の公開行だけ回収する。
        for (UUID key : ownWriteKeys) {
            var saved = commands.findByActorUserIdAndIdempotencyKey(administrator, key);
            if (saved.isEmpty()) continue;
            var command = saved.orElseThrow();
            var ack = json.readTree(command.getResultJson());
            transaction.executeWithoutResult(ignored -> {
                if (command.getCommandType().equals("ADMIN_POLICY_PUBLISH")) {
                    policies.deleteById(UUID.fromString(ack.path("id").textValue()));
                } else if (command.getCommandType().equals("ADMIN_CARE_PUBLISH")) {
                    rules.deleteById(UUID.fromString(ack.path("id").textValue()));
                }
                commands.deleteById(command.getId());
            });
        }
        transaction.executeWithoutResult(ignored -> {
            if (controlFixtureChanged) {
                if (originalControl == null) controls.deleteById(1);
                else controls.saveAndFlush(originalControl);
            }
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:administrator, :ordinary)")
                    .setParameter("administrator", administrator).setParameter("ordinary", ordinary).executeUpdate();
            // 他試験のrole定義・公開ルール・管理commandは変更しない。
            users.deleteById(administrator);
            users.deleteById(ordinary);
            users.flush();
        });
    }

    @Test
    void anonymousCannotReachControlPlane() throws Exception {
        mvc.perform(get(HISTORY)).andExpect(status().isUnauthorized());
    }

    @Test
    void memberAndScopedAdminAuthoritiesCannotReachControlPlane() throws Exception {
        for (String role : new String[]{"MEMBER", "ADMIN"}) {
            mvc.perform(get(HISTORY).with(user(ordinary.toString()).roles(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void systemAdminAuthorityWithoutCurrentDbQualificationIsRejected() throws Exception {
        mvc.perform(get(HISTORY).with(user(ordinary.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        assertThat(owners.findByUserId(ordinary)).isEmpty();
    }

    @Test
    void freshActiveSystemAdminCanReadWithoutCreatingOwner() throws Exception {
        mvc.perform(get(HISTORY).with(user(administrator.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.data").isArray());
        assertThat(owners.findByUserId(administrator)).isEmpty();
    }

    @Test
    void 未参加の現役管理者が三管理命令を保存しても個人ownerを作らない() throws Exception {
        originalControl = controls.findById(1).orElse(null);
        controlFixtureChanged = true;
        RanchTestFixture.operationalControl(controls);
        Instant futureWeek = Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        var sources = Arrays.stream(RanchRewardSourceType.values())
                .map(type -> new RanchPolicyPublicationRequest.SourceRule(type, false, "1", 1)).toList();
        var policy = new RanchPolicyPublicationRequest(futureWeek, false, "100", sources,
                new RanchPolicyPublicationRequest.Delivery(10, 30, 3, 1, 60), "ADMIN_UNENROLLED_TEST");
        UUID policyKey = ownWriteKey();
        assertThat(owners.findByUserId(administrator)).isEmpty();

        var policyResult = mvc.perform(post("/api/v1/system-admin/ranch/policies")
                .with(user(administrator.toString()).roles("SYSTEM_ADMIN"))
                .header("Idempotency-Key", policyKey).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(policy)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode policyAck = json.readTree(policyResult.getResponse().getContentAsByteArray()).path("data");
        assertThat(policyAck.path("settings")).isEqualTo(json.valueToTree(policy));
        assertThat(policyAck.path("publishedBy").textValue()).isEqualTo(administrator.toString());
        assertSavedAdminAck(policyKey, "ADMIN_POLICY_PUBLISH", policyAck);
        assertThat(owners.findByUserId(administrator)).isEmpty();

        var care = new RanchCareRulePublicationRequest(futureWeek, "20", "100", "60", "100", "ADMIN_UNENROLLED_TEST");
        UUID careKey = ownWriteKey();
        assertThat(owners.findByUserId(administrator)).isEmpty();
        var careResult = mvc.perform(post(HISTORY)
                .with(user(administrator.toString()).roles("SYSTEM_ADMIN"))
                .header("Idempotency-Key", careKey).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(care)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode careAck = json.readTree(careResult.getResponse().getContentAsByteArray()).path("data");
        assertThat(careAck.path("settings")).isEqualTo(json.valueToTree(care));
        assertThat(careAck.path("publishedBy").textValue()).isEqualTo(administrator.toString());
        assertSavedAdminAck(careKey, "ADMIN_CARE_PUBLISH", careAck);
        assertThat(owners.findByUserId(administrator)).isEmpty();

        var loaded = mvc.perform(get("/api/v1/system-admin/ranch/operational-controls")
                        .with(user(administrator.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk()).andReturn();
        JsonNode current = json.readTree(loaded.getResponse().getContentAsByteArray()).path("data");
        var request = new RanchOperationalControlsRequest(current.path("version").textValue(),
                current.path("isCareEnabled").booleanValue(), current.path("isShopEnabled").booleanValue(),
                current.path("isDeliveryPaused").booleanValue(), current.path("isRewardsPaused").booleanValue(),
                "ADMIN_UNENROLLED_TEST");
        UUID controlsKey = ownWriteKey();
        assertThat(owners.findByUserId(administrator)).isEmpty();
        var controlsResult = mvc.perform(put("/api/v1/system-admin/ranch/operational-controls")
                .with(user(administrator.toString()).roles("SYSTEM_ADMIN"))
                .header("Idempotency-Key", controlsKey).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn();
        JsonNode controlsAck = json.readTree(controlsResult.getResponse().getContentAsByteArray()).path("data");
        assertThat(Long.parseLong(controlsAck.path("version").textValue()))
                .isEqualTo(Long.parseLong(request.version()) + 1);
        for (String field : List.of("isCareEnabled", "isShopEnabled", "isDeliveryPaused", "isRewardsPaused")) {
            assertThat(controlsAck.path(field)).isEqualTo(current.path(field));
        }
        assertSavedAdminAck(controlsKey, "ADMIN_CONTROLS_UPDATE", controlsAck);
        assertThat(owners.findByUserId(administrator)).isEmpty();
    }

    private UUID ownWriteKey() {
        UUID key = UUID.randomUUID();
        ownWriteKeys.add(key);
        return key;
    }

    private void assertSavedAdminAck(UUID key, String kind, JsonNode ack) throws Exception {
        var command = commands.findByActorUserIdAndIdempotencyKey(administrator, key).orElseThrow();
        assertThat(command.getActorUserId()).isEqualTo(administrator);
        assertThat(command.getCommandType()).isEqualTo(kind);
        assertThat(command.getIdempotencyKey()).isEqualTo(key);
        assertThat(command.getBodyHash()).hasSize(32);
        assertThat(command.getCompletedAt()).isNotNull();
        assertThat(json.readTree(command.getResultJson())).isEqualTo(ack);
    }

    @Test
    void revokedDbQualificationIsRejectedWithUnchangedPrincipalAuthority() throws Exception {
        mvc.perform(get(HISTORY).with(user(administrator.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk());
        transaction.executeWithoutResult(ignored -> em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :id")
                .setParameter("id", administrator).executeUpdate());
        mvc.perform(get(HISTORY).with(user(administrator.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    void frozenCurrentSystemAdminCannotRead() throws Exception {
        transaction.executeWithoutResult(ignored -> {
            var account = users.findById(administrator).orElseThrow();
            account.freeze();
            users.saveAndFlush(account);
        });
        mvc.perform(get(HISTORY).with(user(administrator.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("AUTHOPERATION_002"));
    }

    @Test
    void impersonationCannotReachControlPlane() throws Exception {
        mvc.perform(get(HISTORY).with(user(administrator.toString()).roles("SYSTEM_ADMIN"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, ordinary.toString()))
                .andExpect(status().isForbidden());
    }
}
