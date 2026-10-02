package com.mannschaft.app.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
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
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 承認・紙撤回を実DBと有効なSecurity filterで検証する。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("代理同意管理の承認・撤回契約")
class ProxyConsentManagementMutationContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ProxyInputConsentRepository consents;
    @Autowired private ProxyInputRecordRepository records;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private RolePermissionRepository rolePermissions;
    @PersistenceContext private EntityManager em;
    private ProxyConsentManagementTestFixture fixture;
    private Long organization;
    private Long otherOrganization;
    private Long admin;
    private Long subject;
    private Long proxy;
    private Long consentId;

    @BeforeEach
    void 準備() {
        fixture = new ProxyConsentManagementTestFixture(users, organizations, consents, records);
        organization = fixture.organization();
        otherOrganization = fixture.organization();
        admin = fixture.account();
        subject = fixture.account();
        proxy = fixture.account();
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", null, organization);
        var role = roles.findByName("ADMIN").orElseThrow();
        var existing = permissions.findByNameIn(List.of("PROXY_CONSENT_APPROVE"));
        var permission = existing.isEmpty()
                ? permissions.save(PermissionEntity.builder().name("PROXY_CONSENT_APPROVE")
                        .displayName("代理同意承認").scope(PermissionEntity.Scope.ORGANIZATION).build())
                : existing.getFirst();
        if (rolePermissions.findByRoleId(role.getId()).stream()
                .noneMatch(value -> value.getPermissionId().equals(permission.getId()))) {
            rolePermissions.save(RolePermissionEntity.builder().roleId(role.getId())
                    .permissionId(permission.getId()).isDefault(true).build());
        }
        consentId = fixture.consent(organization, subject, proxy).getId();
        em.flush();
        em.clear();
    }

    @Test
    void 承認して一覧を再取得() throws Exception {
        mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId)
                        .with(user(admin.toString())).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
        mvc.perform(get("/api/v1/organizations/{org}/proxy-input-consents", organization)
                        .with(user(admin.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("APPROVED"));
    }

    @Test
    void 自己承認は権限を持っていても拒否() throws Exception {
        MembershipTestHelper.insertUserRole(em, proxy, "ADMIN", null, organization);
        mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId)
                        .with(user(proxy.toString())).with(csrf()))
                .andExpect(status().isForbidden());
        em.clear();
        assertThat(consents.findById(consentId).orElseThrow().getApprovedAt()).isNull();
    }

    @Test
    void 既承認を再承認して承認者や日時を上書きしない() throws Exception {
        var consent = consents.findById(consentId).orElseThrow();
        consent.approve(proxy);
        consents.saveAndFlush(consent);
        em.refresh(consent);
        var approvedAt = consent.getApprovedAt();
        mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId)
                        .with(user(admin.toString())).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMON_003"));
        em.clear();
        var saved = consents.findById(consentId).orElseThrow();
        assertThat(saved.getApprovedByUserId()).isEqualTo(proxy);
        assertThat(saved.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    void 撤回済み同意を承認しない() throws Exception {
        var consent = consents.findById(consentId).orElseThrow();
        consent.revoke(ProxyInputConsentEntity.RevokeMethod.API_BY_SUBJECT, null, "本人撤回");
        consents.saveAndFlush(consent);
        mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId)
                        .with(user(admin.toString())).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void 紙撤回の証人と255文字理由を保存し一覧に返す() throws Exception {
        String reason = "あ".repeat(255);
        revoke(admin, consentId, Map.of("revokeMethod", "PAPER_BY_SUBJECT",
                "revokeWitnessedByUserId", admin, "revokeReason", reason))
                .andExpect(status().isOk());
        em.flush();
        em.clear();
        var saved = consents.findById(consentId).orElseThrow();
        assertThat(saved.getRevokeMethod()).isEqualTo(ProxyInputConsentEntity.RevokeMethod.PAPER_BY_SUBJECT);
        assertThat(saved.getRevokeWitnessedByUserId()).isEqualTo(admin);
        assertThat(saved.getRevokeReason()).isEqualTo(reason);
        mvc.perform(get("/api/v1/organizations/{org}/proxy-input-consents", organization)
                        .with(user(admin.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.data[0].revokeWitnessedByUserId").value(admin))
                .andExpect(jsonPath("$.data[0].revokeReason").value(reason));
    }

    @Test
    void 本人のAPI撤回を維持() throws Exception {
        revoke(subject, consentId, Map.of("revokeMethod", "API_BY_SUBJECT"))
                .andExpect(status().isOk());
    }

    @Test
    void 管理者が本人のAPI撤回を装えない() throws Exception {
        revoke(admin, consentId, Map.of("revokeMethod", "API_BY_SUBJECT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 紙の証人なしと理由256文字を拒否() throws Exception {
        revoke(admin, consentId, Map.of("revokeMethod", "PAPER_BY_SUBJECT"))
                .andExpect(status().isBadRequest());
        revoke(admin, consentId, Map.of("revokeMethod", "PAPER_BY_SUBJECT",
                "revokeWitnessedByUserId", admin, "revokeReason", "あ".repeat(256)))
                .andExpect(status().isBadRequest());
        em.clear();
        assertThat(consents.findById(consentId).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void 紙撤回の証人は別人でも有効な同組合管理資格を必要とする() throws Exception {
        Long witness = fixture.account();
        MembershipTestHelper.insertUserRole(em, witness, "ADMIN", null, organization);
        revoke(admin, consentId, Map.of("revokeMethod", "PAPER_BY_SUBJECT",
                "revokeWitnessedByUserId", witness)).andExpect(status().isOk());
        em.flush();
        em.clear();
        assertThat(consents.findById(consentId).orElseThrow().getRevokeWitnessedByUserId()).isEqualTo(witness);
    }

    @Test
    void 非管理者や別組合の証人を紙撤回のpayloadに偽装できない() throws Exception {
        Long foreignWitness = fixture.account();
        MembershipTestHelper.insertUserRole(em, foreignWitness, "ADMIN", null, otherOrganization);
        for (Long witness : List.of(subject, foreignWitness, Long.MAX_VALUE)) {
            revoke(admin, consentId, Map.of("revokeMethod", "PAPER_BY_SUBJECT",
                    "revokeWitnessedByUserId", witness)).andExpect(status().isBadRequest());
        }
        em.clear();
        assertThat(consents.findById(consentId).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void 未知の撤回方法と自動方法の手動指定を拒否() throws Exception {
        for (String method : List.of("UNKNOWN", "AUTO_BY_LIFE_EVENT", "AUTO_BY_TENURE_END")) {
            revoke(subject, consentId, Map.of("revokeMethod", method)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void 再撤回で理由を上書きしない() throws Exception {
        var consent = consents.findById(consentId).orElseThrow();
        consent.revoke(ProxyInputConsentEntity.RevokeMethod.API_BY_SUBJECT, null, "最初の理由");
        consents.saveAndFlush(consent);
        revoke(subject, consentId, Map.of("revokeMethod", "API_BY_SUBJECT", "revokeReason", "上書き"))
                .andExpect(status().isConflict());
        em.clear();
        assertThat(consents.findById(consentId).orElseThrow().getRevokeReason()).isEqualTo("最初の理由");
    }

    @Test
    void 越境と不在の承認拒否は同一契約() throws Exception {
        Long foreign = fixture.consent(otherOrganization, subject, proxy).getId();
        MvcResult crossed = mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", foreign)
                        .with(user(admin.toString())).with(csrf())).andExpect(status().isForbidden()).andReturn();
        MvcResult missing = mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", Long.MAX_VALUE)
                        .with(user(admin.toString())).with(csrf())).andExpect(status().isForbidden()).andReturn();
        assertThat(json.readTree(crossed.getResponse().getContentAsString()).get("error"))
                .isEqualTo(json.readTree(missing.getResponse().getContentAsString()).get("error"));
    }

    @Test
    void 越境と不在の撤回拒否は同一契約() throws Exception {
        Long foreign = fixture.consent(otherOrganization, subject, proxy).getId();
        var body = Map.of("revokeMethod", "PAPER_BY_SUBJECT", "revokeWitnessedByUserId", admin);
        MvcResult crossed = revoke(admin, foreign, body).andExpect(status().isForbidden()).andReturn();
        MvcResult missing = revoke(admin, Long.MAX_VALUE, body).andExpect(status().isForbidden()).andReturn();
        assertThat(json.readTree(crossed.getResponse().getContentAsString()).get("error"))
                .isEqualTo(json.readTree(missing.getResponse().getContentAsString()).get("error"));
    }

    private org.springframework.test.web.servlet.ResultActions revoke(Long actor, Long id, Map<String, ?> body)
            throws Exception {
        return mvc.perform(patch("/api/v1/proxy-input-consents/{id}/revoke", id)
                .with(user(actor.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }
}
