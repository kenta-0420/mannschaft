package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260820-1018 代理入力同意書管理・実行履歴 API の受け入れ契約試験。
 *
 * <p>実 SecurityFilterChain、Service、Repository、Testcontainers MySQL を MockMvc から通し、
 * 管理画面が依存するページング形状、組合境界、SYSTEM_ADMIN の BE 横断権限、状態競合を固定する。
 * 同意書一覧と実行履歴を取り違えた実装では、履歴の陽性対照が必ず失敗する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260820-1018 代理入力管理 API 契約試験")
class ProxyInputAdminApiContractIT extends AbstractMySqlIntegrationTest {

    private static final int MAX_PAGE_SIZE = 100;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProxyInputConsentRepository consentRepository;

    @Autowired
    private ProxyInputRecordRepository recordRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long adminAId;
    private Long deputyAId;
    private Long adminBId;
    private Long systemAdminId;
    private Long subjectId;
    private Long proxyId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("CMP1018 組合A");
        orgBId = insertOrganization("CMP1018 組合B");
        adminAId = insertUser("cmp1018-admin-a@example.com");
        deputyAId = insertUser("cmp1018-deputy-a@example.com");
        adminBId = insertUser("cmp1018-admin-b@example.com");
        systemAdminId = insertUser("cmp1018-system-admin@example.com");
        subjectId = insertUser("cmp1018-subject@example.com");
        proxyId = insertUser("cmp1018-proxy@example.com");
        outsiderId = insertUser("cmp1018-outsider@example.com");

        grantOrganizationRole(adminAId, "ADMIN", orgAId);
        grantOrganizationRole(deputyAId, "DEPUTY_ADMIN", orgAId);
        grantOrganizationRole(adminBId, "ADMIN", orgBId);
        grantOrganizationRole(proxyId, "ADMIN", orgAId);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);
        seedApprovePermissionForAdmin();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("AC-1: 同意書一覧はpage/sizeを適用しdata配列とmetaを返す")
    void 同意書一覧はPagedResponse正本を返す() throws Exception {
        saveConsent(orgAId, subjectId, proxyId);
        saveConsent(orgAId, outsiderId, proxyId);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/proxy-input-consents", orgAId)
                        .param("page", "0")
                        .param("size", "1")
                        .with(user(adminAId.toString()).roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
    }

    @Test
    @DisplayName("AC-1b: size上限超過は100件へ制限される")
    void 同意書一覧のsizeは上限へ制限される() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/proxy-input-consents", orgAId)
                        .param("page", "0")
                        .param("size", "101")
                        .with(user(adminAId.toString()).roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(MAX_PAGE_SIZE));
    }

    @Test
    @DisplayName("AC-1c: 空の同意書一覧もdata空配列とゼロ件metaを返す")
    void 空の同意書一覧もPagedResponse正本を返す() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/proxy-input-consents", orgAId)
                        .param("page", "0")
                        .param("size", "20")
                        .with(user(adminAId.toString()).roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalPages").value(0));
    }

    @Test
    @DisplayName("AC-2a: ADMINとDEPUTY_ADMINは所属組合の同意書だけ参照できる")
    void 所属組合の管理者は同意書一覧を参照できる() throws Exception {
        saveConsent(orgAId, subjectId, proxyId);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/proxy-input-consents", orgAId)
                        .with(user(adminAId.toString()).roles("MEMBER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/organizations/{orgId}/proxy-input-consents", orgAId)
                        .with(user(deputyAId.toString()).roles("MEMBER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/organizations/{orgId}/proxy-input-consents", orgAId)
                        .with(user(adminBId.toString()).roles("MEMBER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-2b: SYSTEM_ADMINは所属なしでもBEから任意組合の同意書を参照できる")
    void SYSTEM_ADMINは全組合の同意書を参照できる() throws Exception {
        saveConsent(orgAId, subjectId, proxyId);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/proxy-input-consents", orgAId)
                        .with(user(systemAdminId.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("AC-3a: PROXY_CONSENT_APPROVE保有ADMINは承認できるが代理者本人は自己承認できない")
    void 承認権限と自己承認禁止を強制する() throws Exception {
        ProxyInputConsentEntity consent = saveConsent(orgAId, subjectId, proxyId);

        mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consent.getId())
                        .with(user(proxyId.toString()).roles("MEMBER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consent.getId())
                        .with(user(adminAId.toString()).roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("AC-3a-2: PROXY_CONSENT_APPROVEを持たないDEPUTY_ADMINは承認できない")
    void 承認権限を持たないDEPUTY_ADMINは拒否される() throws Exception {
        ProxyInputConsentEntity consent = saveConsent(orgAId, subjectId, outsiderId);

        approve(consent.getId(), deputyAId).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-3b: SYSTEM_ADMINは所属なしでも同意書を承認できる")
    void SYSTEM_ADMINは同意書を承認できる() throws Exception {
        ProxyInputConsentEntity consent = saveConsent(orgAId, subjectId, proxyId);

        mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consent.getId())
                        .with(user(systemAdminId.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("AC-4a: 本人または所属組合ADMIN/DEPUTYのみ撤回できる")
    void 撤回の主体を本人と所属組合管理者へ限定する() throws Exception {
        ProxyInputConsentEntity bySubject = saveConsent(orgAId, subjectId, proxyId);
        ProxyInputConsentEntity byDeputy = saveConsent(orgAId, outsiderId, proxyId);
        ProxyInputConsentEntity crossTenant = saveConsent(orgAId, adminBId, proxyId);

        revoke(bySubject.getId(), subjectId).andExpect(status().isOk());
        revoke(byDeputy.getId(), deputyAId).andExpect(status().isOk());
        revoke(crossTenant.getId(), adminBId).andExpect(status().isOk());

        ProxyInputConsentEntity denied = saveConsent(orgAId, subjectId, outsiderId);
        revoke(denied.getId(), adminBId).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-4b: SYSTEM_ADMINは所属なしでも同意書を撤回できる")
    void SYSTEM_ADMINは同意書を撤回できる() throws Exception {
        ProxyInputConsentEntity consent = saveConsent(orgAId, subjectId, proxyId);

        revoke(consent.getId(), systemAdminId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-4c/6: 撤回済み同意書への二重送信は409で偽成功しない")
    void 撤回の二重送信は競合になる() throws Exception {
        ProxyInputConsentEntity consent = saveConsent(orgAId, subjectId, proxyId);

        revoke(consent.getId(), subjectId).andExpect(status().isOk());
        revoke(consent.getId(), subjectId).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AC-3c/6: 承認済み同意書への二重送信は409で偽成功しない")
    void 承認の二重送信は競合になる() throws Exception {
        ProxyInputConsentEntity consent = saveConsent(orgAId, subjectId, proxyId);

        approve(consent.getId(), adminAId).andExpect(status().isOk());
        approve(consent.getId(), adminAId).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AC-5a: 履歴APIは実proxy_input_recordsを組合境界付きでページングする")
    void 履歴APIは実行記録を組合境界付きで返す() throws Exception {
        ProxyInputConsentEntity consentA = saveConsent(orgAId, subjectId, proxyId);
        ProxyInputConsentEntity consentB = saveConsent(orgBId, subjectId, proxyId);
        saveRecord(consentA.getId(), subjectId, proxyId, 1101L);
        saveRecord(consentB.getId(), subjectId, proxyId, 2201L);
        saveRecord(null, subjectId, proxyId, 3301L);

        mockMvc.perform(get("/api/v1/proxy-input-records")
                        .param("organizationId", orgAId.toString())
                        .param("page", "0")
                        .param("size", "20")
                        .with(user(adminAId.toString()).roles("MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].targetEntityType").value("SURVEY_RESPONSE"))
                .andExpect(jsonPath("$.data[0].targetEntityId").value(1101))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
    }

    @Test
    @DisplayName("AC-5b: 別組合のADMINは履歴組合IDを書き換えても参照できない")
    void 履歴APIは他組合への越境を拒否する() throws Exception {
        mockMvc.perform(get("/api/v1/proxy-input-records")
                        .param("organizationId", orgAId.toString())
                        .param("page", "0")
                        .param("size", "20")
                        .with(user(adminBId.toString()).roles("MEMBER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-5c: SYSTEM_ADMINは所属なしでも任意組合の実行履歴を参照できる")
    void SYSTEM_ADMINは全組合の実行履歴を参照できる() throws Exception {
        ProxyInputConsentEntity consent = saveConsent(orgBId, subjectId, proxyId);
        saveRecord(consent.getId(), subjectId, proxyId, 2201L);

        mockMvc.perform(get("/api/v1/proxy-input-records")
                        .param("organizationId", orgBId.toString())
                        .param("page", "0")
                        .param("size", "20")
                        .with(user(systemAdminId.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].targetEntityId").value(2201));
    }

    @Test
    @DisplayName("AC-2c/3d/4d: 別組合ADMINは同意書IDを直指定しても承認・撤回できない")
    void 他組合の同意書IDへの更新を拒否する() throws Exception {
        ProxyInputConsentEntity approveTarget = saveConsent(orgAId, subjectId, outsiderId);
        ProxyInputConsentEntity revokeTarget = saveConsent(orgAId, outsiderId, subjectId);

        approve(approveTarget.getId(), adminBId).andExpect(status().isForbidden());
        revoke(revokeTarget.getId(), adminBId).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-2d: 未認証の管理APIアクセスは401")
    void 未認証アクセスは拒否される() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/proxy-input-consents", orgAId))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions approve(Long consentId, Long actorId)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId)
                .with(user(actorId.toString()).roles("MEMBER")));
    }

    private org.springframework.test.web.servlet.ResultActions revoke(Long consentId, Long actorId)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/proxy-input-consents/{id}/revoke", consentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revokeMethod\":\"API_BY_SUBJECT\",\"revokeReason\":\"契約試験\"}")
                .with(user(actorId.toString()).roles("MEMBER")));
    }

    private ProxyInputConsentEntity saveConsent(Long organizationId, Long subjectUserId, Long proxyUserId) {
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
        return consentRepository.saveAndFlush(consent);
    }

    private void saveRecord(Long consentId, Long subjectUserId, Long proxyUserId, Long targetEntityId) {
        recordRepository.saveAndFlush(ProxyInputRecordEntity.create(
                consentId,
                subjectUserId,
                proxyUserId,
                "SURVEY",
                "SURVEY_RESPONSE",
                targetEntityId,
                ProxyInputRecordEntity.InputSource.PAPER_FORM,
                "管理組合書庫"));
    }

    private void grantOrganizationRole(Long userId, String roleName, Long organizationId) {
        MembershipTestHelper.insertMembership(
                em, userId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, roleName, null, organizationId);
    }

    private void seedApprovePermissionForAdmin() {
        PermissionEntity permission = permissionRepository.findByNameIn(List.of("PROXY_CONSENT_APPROVE")).stream()
                .findFirst()
                .orElseGet(() -> permissionRepository.saveAndFlush(PermissionEntity.builder()
                        .name("PROXY_CONSENT_APPROVE")
                        .displayName("代理入力同意書承認")
                        .scope(PermissionEntity.Scope.ORGANIZATION)
                        .build()));
        Long adminRoleId = roleRepository.findByName("ADMIN").orElseThrow().getId();
        boolean alreadyGranted = rolePermissionRepository.findByRoleId(adminRoleId).stream()
                .anyMatch(rolePermission -> rolePermission.getPermissionId().equals(permission.getId()));
        if (!alreadyGranted) {
            rolePermissionRepository.saveAndFlush(RolePermissionEntity.builder()
                    .roleId(adminRoleId)
                    .permissionId(permission.getId())
                    .isDefault(true)
                    .build());
        }
    }

    private Long insertUser(String email) {
        UserEntity userEntity = UserEntity.builder()
                .email(email)
                .lastName("CMP1018")
                .firstName("試練")
                .displayName("CMP1018 試練")
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(userEntity);
        em.flush();
        return userEntity.getId();
    }

    private Long insertOrganization(String name) {
        OrganizationEntity organization = OrganizationEntity.builder()
                .slug("cmp1018-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                .name(name)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(organization);
        em.flush();
        return organization.getId();
    }
}
