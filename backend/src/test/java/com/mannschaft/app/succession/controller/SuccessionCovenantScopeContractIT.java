package com.mannschaft.app.succession.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.succession.entity.SuccessionCovenantEntity;
import com.mannschaft.app.succession.repository.SuccessionCovenantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260820-1422 の誓約取得・撤回に限定した本文契約。
 *
 * <p>実JWT・フィルタ・Controller・Service・認可・MySQL・GHEを通す。
 * 不在と非許可の本文同値を検証し、正常系・scope分離・一覧403も固定する。
 * MockMvc を使うため、共通基底の MOCK 設定と外部依存モックを継承する。
 * ログイン自体、他Succession API、他ドメイン、応答時間の同値を保証しない。
 * Fixtureは独立TXで確定し、業務TXのロールバックでDB不変を代用しない。
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP1422 SuccessionCovenantController MockMvc本文・scope契約")
class SuccessionCovenantScopeContractIT extends AbstractSuccessionIntegrationTest {

    private static final UUID MISSING_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String FEATURE_KEY = "FEATURE_SUCCESSION_PROXY_ENABLED";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private OrganizationRepository organizations;
    @Autowired private DwellingUnitRepository dwellings;
    @Autowired private ResidentRegistryRepository residents;
    @Autowired private SuccessionCovenantRepository covenants;
    @Autowired private FeatureFlagRepository featureFlags;
    @Autowired private CacheManager cacheManager;

    private final List<Long> actorIds = new ArrayList<>();
    private final List<Long> organizationIds = new ArrayList<>();
    private final List<Long> dwellingIds = new ArrayList<>();
    private final List<Long> residentIds = new ArrayList<>();
    /** この IT が新規作成した roles 行。既存行を get した場合は含めない（他 IT の get-or-create 前提を壊さないため）。 */
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final List<UUID> covenantIds = new ArrayList<>();
    private Long signer;
    private Long nonAdmin;
    private Long admin;
    private Long deputy;
    private Long foreignSigner;
    private Long ownOrg;
    private Long foreignOrg;
    private UUID ownCovenant;
    private UUID foreignCovenant;
    private FlagState previousFlag;
    private Long fixtureFlagId;
    private boolean createdFlag;
    private boolean ownerRevokeRequested;

    @BeforeEach
    void fixtureを独立TXで保存して実JWTの失効無し状態を用意する() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        SecurityContextHolder.clearContext();
        // 本物JWT署名・検証は通す。外部Valkeyだけ既基底のモックを利用する。
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        inTx(() -> {
            signer = fixtureUser("signer");
            nonAdmin = fixtureUser("non-admin");
            admin = fixtureUser("admin");
            deputy = fixtureUser("deputy");
            foreignSigner = fixtureUser("foreign-signer");
            ownOrg = fixtureOrganization("own");
            foreignOrg = fixtureOrganization("foreign");
            for (Long actor : List.of(signer, nonAdmin, admin, deputy)) {
                fixtureMembership(actor, ownOrg);
            }
            fixtureMembership(foreignSigner, foreignOrg);
            fixtureUserRole(admin, "ADMIN", ownOrg);
            fixtureUserRole(deputy, "DEPUTY_ADMIN", ownOrg);
            ownCovenant = fixtureCovenant(ownOrg, signer, "own");
            foreignCovenant = fixtureCovenant(foreignOrg, foreignSigner, "foreign");
            em.flush();
            assertThat(covenants.existsById(MISSING_ID)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 自所有fixtureを削除して共有flagを元の状態へ復元する() {
        try {
            // recordは非同期。本人物の撤回で監査が始まった場合は完了後に自所有userを消す。
            if (ownerRevokeRequested && ownCovenant != null
                    && inTx(() -> covenants.findById(ownCovenant)
                            .map(c -> c.getRevokedAt() != null).orElse(false))) {
                await().atMost(Duration.ofSeconds(10)).until(() -> inTx(() ->
                        ((Number) em.createNativeQuery(
                                        "SELECT COUNT(*) FROM audit_logs WHERE event_type = 'COVENANT_REVOKED' AND user_id = :id")
                                .setParameter("id", signer).getSingleResult()).longValue() > 0));
            }
            inTx(() -> {
                if (!covenantIds.isEmpty()) {
                    covenants.deleteAllById(covenantIds);
                }
                if (!residentIds.isEmpty()) {
                    residents.deleteAllById(residentIds);
                }
                if (!dwellingIds.isEmpty()) {
                    dwellings.deleteAllById(dwellingIds);
                }
                em.flush();
                if (!actorIds.isEmpty()) {
                    // 範囲はこのtestが採番したuserだけ。roleはこのtestが新規作成した行だけを後で削除する（既存の共有roleは消さない）。
                    em.createNativeQuery("DELETE FROM audit_logs WHERE user_id IN (:ids)")
                            .setParameter("ids", actorIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)")
                            .setParameter("ids", actorIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)")
                            .setParameter("ids", actorIds).executeUpdate();
                    users.deleteAllById(actorIds);
                }
                roles.deleteAllById(createdRoleIds);
                if (!organizationIds.isEmpty()) {
                    organizations.deleteAllById(organizationIds);
                }
                em.flush();
                return null;
            });
        } finally {
            try {
                restoreFeatureFlag();
            } finally {
                try {
                    evictFeatureFlagCache();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }
    }

    @Test
    void S1_GET同組織非所有者の実在と欠落は404本文全errorが同一でDB不変() throws Exception {
        DbSnapshot before = snapshot();
        MvcResult hidden = http(nonAdmin, HttpMethod.GET, detailPath(ownOrg, ownCovenant))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = http(nonAdmin, HttpMethod.GET, detailPath(ownOrg, MISSING_ID))
                .andExpect(status().isNotFound()).andReturn();

        assertSameNotFound(hidden, missing);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void S2_GET正当ADMINのorgAパスではorgBのIDと欠落が同一でDB不変() throws Exception {
        DbSnapshot before = snapshot();
        MvcResult foreign = http(admin, HttpMethod.GET, detailPath(ownOrg, foreignCovenant))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = http(admin, HttpMethod.GET, detailPath(ownOrg, MISSING_ID))
                .andExpect(status().isNotFound()).andReturn();

        assertSameNotFound(foreign, missing);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void S3_GET署名本人は200で自分の誓約を閲覧できる() throws Exception {
        DbSnapshot before = snapshot();
        http(signer, HttpMethod.GET, detailPath(ownOrg, ownCovenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ownCovenant.toString()));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest(name = "S4 {0} は同orgの他人の誓約を閲覧できる")
    @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN"})
    void S4_GET同組織ADMINとDEPUTYは200で他人の誓約を閲覧できる(String role) throws Exception {
        Long actor = "ADMIN".equals(role) ? admin : deputy;
        DbSnapshot before = snapshot();
        http(actor, HttpMethod.GET, detailPath(ownOrg, ownCovenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ownCovenant.toString()));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void S5_POST非署名者による実在と欠落の撤回は404本文全errorが同一でDB不変() throws Exception {
        DbSnapshot before = snapshot();
        MvcResult hidden = http(nonAdmin, HttpMethod.POST, revokePath(ownCovenant))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = http(nonAdmin, HttpMethod.POST, revokePath(MISSING_ID))
                .andExpect(status().isNotFound()).andReturn();

        assertSameNotFound(hidden, missing);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void S6_POST署名本人は200で撤回日時を正常に更新する() throws Exception {
        assertThat(inTx(() -> covenants.findById(ownCovenant).orElseThrow().getRevokedAt())).isNull();
        ownerRevokeRequested = true;
        http(signer, HttpMethod.POST, revokePath(ownCovenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ownCovenant.toString()))
                .andExpect(jsonPath("$.data.revokedAt").isNotEmpty());

        assertThat(inTx(() -> covenants.findById(ownCovenant).orElseThrow().getRevokedAt())).isNotNull();
        assertThat(inTx(() -> covenants.findById(foreignCovenant).orElseThrow().getRevokedAt())).isNull();
    }

    @Test
    void S7_一覧の同組織非ADMINは本物featuregate通過後も403と023でDB不変() throws Exception {
        enableFeatureFlag();
        DbSnapshot before = snapshot();
        MvcResult denied = http(nonAdmin, HttpMethod.GET, "/api/v1/organizations/" + ownOrg + "/succession/covenants")
                .andExpect(status().isForbidden()).andReturn();

        JsonNode error = error(denied);
        assertThat(error.path("code").asText()).isEqualTo("SUCCESSION_023");
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.path("fieldErrors").isArray()).isTrue();
        assertThat(error.path("fieldErrors").size()).isZero();
        assertThat(snapshot()).isEqualTo(before);
    }

    private org.springframework.test.web.servlet.ResultActions http(Long actor, HttpMethod method, String path) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return mockMvc.perform(request(method, path)
                .header("Accept-Language", "ja")
                .header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER"))));
    }

    private void assertSameNotFound(MvcResult first, MvcResult second) throws Exception {
        assertThat(first.getResponse().getStatus()).isEqualTo(404);
        assertThat(second.getResponse().getStatus()).isEqualTo(404);
        JsonNode firstError = error(first);
        JsonNode secondError = error(second);
        for (JsonNode body : List.of(firstError, secondError)) {
            assertThat(body.path("code").asText()).isEqualTo("SUCCESSION_003");
            assertThat(body.path("message").asText()).isNotBlank();
            assertThat(body.path("fieldErrors").isArray()).isTrue();
            assertThat(body.path("fieldErrors").size()).isZero();
        }
        assertThat(firstError).isEqualTo(secondError);
    }

    private JsonNode error(MvcResult result) throws Exception {
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.isObject()).isTrue();
        assertThat(body.has("data")).isFalse();
        assertThat(body.path("error").isObject()).isTrue();
        return body.path("error");
    }

    private DbSnapshot snapshot() {
        return inTx(() -> {
            em.flush();
            em.clear();
            List<?> rows = em.createNativeQuery(
                            "SELECT * FROM succession_covenants WHERE organization_id IN (:ids) ORDER BY id")
                    .setParameter("ids", organizationIds).getResultList();
            long count = ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM succession_covenants WHERE organization_id IN (:ids)")
                    .setParameter("ids", organizationIds).getSingleResult()).longValue();
            return new DbSnapshot(Arrays.deepToString(rows.toArray()), count);
        });
    }

    private void enableFeatureFlag() {
        inTx(() -> {
            FeatureFlagEntity flag = featureFlags.findByFlagKey(FEATURE_KEY).orElse(null);
            if (flag == null) {
                flag = featureFlags.save(FeatureFlagEntity.builder()
                        .flagKey(FEATURE_KEY).isEnabled(true).description("CMP1422誓約契約fixture").build());
                createdFlag = true;
            } else {
                previousFlag = new FlagState(flag.getId(), flag.getIsEnabled(), flag.getUpdatedBy(), flag.getUpdatedAt());
                flag.updateFlag(true, flag.getUpdatedBy());
            }
            fixtureFlagId = flag.getId();
            em.flush();
            return null;
        });
        evictFeatureFlagCache();
    }

    private void evictFeatureFlagCache() {
        if (fixtureFlagId == null) {
            return;
        }
        Cache cache = cacheManager.getCache("featureFlags");
        if (cache != null) {
            cache.evict(FEATURE_KEY);
        }
    }

    private void restoreFeatureFlag() {
        if (fixtureFlagId == null) {
            return;
        }
        inTx(() -> {
            if (createdFlag) {
                FeatureFlagEntity current = featureFlags.findById(fixtureFlagId).orElseThrow();
                assertThat(current.getFlagKey()).isEqualTo(FEATURE_KEY);
                featureFlags.delete(current);
            } else {
                // enabledだけでなく変更したmetadataも保存前の値へ戻す。共有行は削除しない。
                int restored = em.createNativeQuery(
                                "UPDATE feature_flags SET is_enabled = :enabled, updated_by = :actor, updated_at = :updated"
                                        + " WHERE id = :id AND flag_key = :key")
                        .setParameter("enabled", previousFlag.enabled())
                        .setParameter("actor", previousFlag.updatedBy())
                        .setParameter("updated", previousFlag.updatedAt())
                        .setParameter("id", previousFlag.id())
                        .setParameter("key", FEATURE_KEY).executeUpdate();
                assertThat(restored).isEqualTo(1);
            }
            return null;
        });
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long fixtureUser(String label) {
        Long id = users.save(CovenantFixtures.user(label)).getId();
        actorIds.add(id);
        return id;
    }

    private Long fixtureRole(String name) {
        return roles.findByName(name).orElseGet(() -> {
            RoleEntity created = roles.save(CovenantFixtures.role(name));
            createdRoleIds.add(created.getId());
            return created;
        }).getId();
    }

    private void fixtureMembership(Long actor, Long orgId) {
        fixtureRole("MEMBER");
        memberships.save(CovenantFixtures.membership(actor, orgId));
    }

    private void fixtureUserRole(Long actor, String name, Long orgId) {
        userRoles.save(CovenantFixtures.userRole(actor, fixtureRole(name), orgId));
    }

    private Long fixtureOrganization(String label) {
        Long id = organizations.save(CovenantFixtures.organization(label)).getId();
        organizationIds.add(id);
        return id;
    }

    private UUID fixtureCovenant(Long orgId, Long actor, String label) {
        DwellingUnitEntity unit = dwellings.save(CovenantFixtures.dwelling(orgId, label));
        dwellingIds.add(unit.getId());
        ResidentRegistryEntity resident = residents.save(CovenantFixtures.resident(unit.getId(), actor, label));
        residentIds.add(resident.getId());
        SuccessionCovenantEntity covenant = covenants.save(CovenantFixtures.covenant(orgId, actor, unit.getId(), resident.getId()));
        covenantIds.add(covenant.getId());
        return covenant.getId();
    }

    private String detailPath(Long orgId, UUID id) {
        return "/api/v1/organizations/" + orgId + "/succession/covenants/" + id;
    }

    private String revokePath(UUID id) {
        return "/api/v1/succession/covenants/" + id + "/revoke";
    }

    private record DbSnapshot(String rows, long count) { }
    private record FlagState(Long id, Boolean enabled, Long updatedBy, LocalDateTime updatedAt) { }

    /** 領域内fixtureの型と必須値を一箇所に置く。既Oracleのuser/org生成形を限定再利用。 */
    private static final class CovenantFixtures {
        private static RoleEntity role(String name) {
            return RoleEntity.builder().name(name).displayName(name)
                    .priority(RolePriority.priority(name)).isSystem(false).build();
        }

        private static MembershipEntity membership(Long actor, Long orgId) {
            return MembershipEntity.builder().userId(actor).scopeType(ScopeType.ORGANIZATION)
                    .scopeId(orgId).roleKind(RoleKind.MEMBER).build();
        }

        private static UserRoleEntity userRole(Long actor, Long roleId, Long orgId) {
            return UserRoleEntity.builder().userId(actor).roleId(roleId).organizationId(orgId).build();
        }

        private static UserEntity user(String label) {
            return UserEntity.builder().email("succession-oracle-" + label + "-" + UUID.randomUUID() + "@example.com")
                    .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build();
        }

        private static OrganizationEntity organization(String label) {
            return OrganizationEntity.builder().slug("succession-oracle-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("誓約試練" + label).orgType(OrganizationEntity.OrgType.OTHER)
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build();
        }

        private static DwellingUnitEntity dwelling(Long orgId, String label) {
            return DwellingUnitEntity.builder().scopeType("ORGANIZATION").organizationId(orgId)
                    .unitNumber(label).unitType("STANDARD").build();
        }

        private static ResidentRegistryEntity resident(Long dwellingId, Long actor, String label) {
            return ResidentRegistryEntity.builder().dwellingUnitId(dwellingId).userId(actor)
                    .residentType("OWNER").lastName("試練").firstName(label)
                    .moveInDate(LocalDate.now()).isPrimary(false).build();
        }

        private static SuccessionCovenantEntity covenant(Long orgId, Long actor, Long dwellingId, Long residentId) {
            return SuccessionCovenantEntity.builder().organizationId(orgId).dwellingUnitId(dwellingId)
                    .residentRegistryId(residentId).signerUserId(actor).covenantType("PRIVACY_CONSENT")
                    .covenantVersion("fixture-v1").pdfS3Key("organizations/" + orgId + "/succession/covenants/fixture.pdf")
                    .pdfSha256("0".repeat(64)).internalSignatureToken("fixture-token")
                    .signedAt(LocalDateTime.now()).build();
        }
    }
}
