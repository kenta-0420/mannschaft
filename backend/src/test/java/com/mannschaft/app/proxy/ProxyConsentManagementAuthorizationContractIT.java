package com.mannschaft.app.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.LeftTrigger;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 既存権限の例外・組合退会・親生存の境界を固定する実DB契約。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ProxyConsentManagementAuthorizationContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ProxyInputConsentRepository consents;
    @Autowired private ProxyInputRecordRepository records;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private RolePermissionRepository rolePermissions;
    @Autowired private MembershipRepository memberships;
    @PersistenceContext private EntityManager em;
    private ProxyConsentManagementTestFixture fixture;
    private Long organization;
    private Long actor;
    private Long subject;
    private Long consentId;

    @BeforeEach
    void 準備() {
        fixture = new ProxyConsentManagementTestFixture(users, organizations, consents, records);
        organization = fixture.organization();
        actor = fixture.account();
        subject = fixture.account();
        consentId = fixture.consent(organization, subject, fixture.account()).getId();
    }

    @Test
    void DEPUTYでも承認権限なしなら拒否() throws Exception {
        MembershipTestHelper.insertUserRole(em, actor, "DEPUTY_ADMIN", null, organization);
        approve().andExpect(status().isForbidden());
    }

    @Test
    void DEPUTYの承認専用権限を尊重() throws Exception {
        MembershipTestHelper.insertUserRole(em, actor, "DEPUTY_ADMIN", null, organization);
        grantApproval("DEPUTY_ADMIN");
        approve().andExpect(status().isOk());
    }

    @Test
    void SYS単独はGate通過で承認権限を獲得しない() throws Exception {
        MembershipTestHelper.insertUserRole(em, actor, "SYSTEM_ADMIN", null, null);
        approve().andExpect(status().isForbidden());
        revoke(actor, Map.of("revokeMethod", "PAPER_BY_SUBJECT", "revokeWitnessedByUserId", actor))
                .andExpect(status().isForbidden());
    }

    @Test
    void SYSとscopeADMIN併有は既存承認権限を保持() throws Exception {
        MembershipTestHelper.insertUserRole(em, actor, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertUserRole(em, actor, "ADMIN", null, organization);
        grantApproval("ADMIN");
        approve().andExpect(status().isOk());
    }

    @Test
    void 組合退会後も本人のAPI撤回を許可() throws Exception {
        memberships.saveAndFlush(MembershipEntity.builder().userId(subject)
                .scopeType(ScopeType.ORGANIZATION).scopeId(organization)
                .joinedAt(LocalDateTime.now().minusDays(10)).leftAt(LocalDateTime.now().minusDays(1))
                .leaveReason(LeaveReason.SELF).leftTrigger(LeftTrigger.SELF).leftBy(subject).build());
        revoke(subject, Map.of("revokeMethod", "API_BY_SUBJECT")).andExpect(status().isOk());
    }

    @Test
    void 無効ユーザーの残存ADMIN資格を立会資格に使えない() throws Exception {
        MembershipTestHelper.insertUserRole(em, actor, "ADMIN", null, organization);
        Long witness = fixture.account();
        MembershipTestHelper.insertUserRole(em, witness, "ADMIN", null, organization);
        var userEntity = users.findById(witness).orElseThrow();
        userEntity.freeze();
        users.saveAndFlush(userEntity);
        revoke(actor, Map.of("revokeMethod", "PAPER_BY_SUBJECT", "revokeWitnessedByUserId", witness))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 親組合削除後は管理資格が残っても承認と撤回を拒否() throws Exception {
        MembershipTestHelper.insertUserRole(em, actor, "ADMIN", null, organization);
        grantApproval("ADMIN");
        deleteParent();
        approve().andExpect(status().isForbidden());
        revoke(actor, Map.of("revokeMethod", "PAPER_BY_SUBJECT", "revokeWitnessedByUserId", actor))
                .andExpect(status().isForbidden());
    }

    @Test
    void 親組合削除はSYS短絡より先に拒否() throws Exception {
        MembershipTestHelper.insertUserRole(em, actor, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertUserRole(em, actor, "ADMIN", null, organization);
        grantApproval("ADMIN");
        deleteParent();
        approve().andExpect(status().isForbidden());
    }

    @Test
    void 未認証の承認と撤回を拒否() throws Exception {
        mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId).with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/proxy-input-consents/{id}/revoke", consentId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("revokeMethod", "API_BY_SUBJECT"))))
                .andExpect(status().isUnauthorized());
    }

    private void deleteParent() {
        var org = organizations.findById(organization).orElseThrow();
        org.softDelete();
        organizations.saveAndFlush(org);
        em.clear();
    }

    private void grantApproval(String roleName) {
        var role = roles.findByName(roleName).orElseThrow();
        var existing = permissions.findByNameIn(List.of("PROXY_CONSENT_APPROVE"));
        var permission = existing.isEmpty()
                ? permissions.save(PermissionEntity.builder().name("PROXY_CONSENT_APPROVE")
                        .displayName("代理同意承認").scope(PermissionEntity.Scope.ORGANIZATION).build())
                : existing.getFirst();
        rolePermissions.saveAndFlush(RolePermissionEntity.builder().roleId(role.getId())
                .permissionId(permission.getId()).isDefault(true).build());
    }

    private org.springframework.test.web.servlet.ResultActions approve() throws Exception {
        return mvc.perform(patch("/api/v1/proxy-input-consents/{id}/approve", consentId)
                .with(user(actor.toString())).with(csrf()));
    }

    private org.springframework.test.web.servlet.ResultActions revoke(Long requester, Map<String, ?> body)
            throws Exception {
        return mvc.perform(patch("/api/v1/proxy-input-consents/{id}/revoke", consentId)
                .with(user(requester.toString())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)));
    }
}
