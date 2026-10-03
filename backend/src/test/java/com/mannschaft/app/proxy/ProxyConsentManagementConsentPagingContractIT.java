package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity.RevokeMethod;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 前任試練の同意一覧・SYS横断裁可を、標準ページング契約として引き継ぐ。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ProxyConsentManagementConsentPagingContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ProxyInputConsentRepository consents;
    @Autowired private ProxyInputRecordRepository records;
    @PersistenceContext private EntityManager em;
    private Long organization;
    private Long emptyOrganization;
    private Long admin;
    private Long deputy;
    private Long foreignAdmin;
    private Long systemAdmin;
    private Long subject;
    private Long pending;
    private Long approved;
    private Long revoked;

    @BeforeEach
    void 準備() {
        var fixture = new ProxyConsentManagementTestFixture(users, organizations, consents, records);
        organization = fixture.organization();
        emptyOrganization = fixture.organization();
        admin = fixture.account();
        deputy = fixture.account();
        foreignAdmin = fixture.account();
        systemAdmin = fixture.account();
        subject = fixture.account();
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", null, organization);
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", null, emptyOrganization);
        MembershipTestHelper.insertUserRole(em, deputy, "DEPUTY_ADMIN", null, organization);
        MembershipTestHelper.insertUserRole(em, foreignAdmin, "ADMIN", null, emptyOrganization);
        MembershipTestHelper.insertUserRole(em, systemAdmin, "SYSTEM_ADMIN", null, null);
        pending = fixture.consent(organization, subject, fixture.account()).getId();
        var approvedConsent = fixture.consent(organization, fixture.account(), fixture.account());
        approvedConsent.approve(admin);
        approved = approvedConsent.getId();
        var revokedConsent = fixture.consent(organization, fixture.account(), fixture.account());
        revokedConsent.revoke(RevokeMethod.PAPER_BY_SUBJECT, admin, "紙撤回");
        revoked = revokedConsent.getId();
        em.flush();
        em.clear();
    }

    @Test
    void 全状態を標準metaと降順のDBページで取得() throws Exception {
        mvc.perform(get(path(), organization).with(user(admin.toString())).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(revoked))
                .andExpect(jsonPath("$.data[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.data[1].id").value(approved))
                .andExpect(jsonPath("$.data[1].status").value("APPROVED"))
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        mvc.perform(get(path(), organization).with(user(admin.toString()))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(pending))
                .andExpect(jsonPath("$.data[0].status").value("PENDING_APPROVAL"));
        mvc.perform(get(path(), organization).with(user(admin.toString()))
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(3));
    }

    @Test
    void 空組合もdataとゼロmetaを返す() throws Exception {
        mvc.perform(get(path(), emptyOrganization).with(user(admin.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.totalPages").value(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 99, 100, 101})
    void sizeの上下限前後は上限100へ制限(int size) throws Exception {
        mvc.perform(get(path(), organization).with(user(admin.toString())).param("size", String.valueOf(size)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.size").value(Math.min(size, 100)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void size下限未満は400(int size) throws Exception {
        mvc.perform(get(path(), organization).with(user(admin.toString())).param("size", String.valueOf(size)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void page下限未満は400() throws Exception {
        mvc.perform(get(path(), organization).with(user(admin.toString())).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ADMINとDEPUTYは自組合のみで本人にも管理資格を要求() throws Exception {
        mvc.perform(get(path(), organization).with(user(deputy.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(3));
        mvc.perform(get(path(), organization).with(user(foreignAdmin.toString())))
                .andExpect(status().isForbidden());
        mvc.perform(get(path(), organization).with(user(subject.toString())))
                .andExpect(status().isForbidden());
    }

    @Test
    void SYS単独は未所属組合の一覧にもアクセスできる() throws Exception {
        mvc.perform(get(path(), organization).with(user(systemAdmin.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(3));
        mvc.perform(get(path(), emptyOrganization).with(user(systemAdmin.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(0));
    }

    @Test
    void 未認証一覧は401() throws Exception {
        mvc.perform(get(path(), organization)).andExpect(status().isUnauthorized());
    }

    private String path() {
        return "/api/v1/organizations/{org}/proxy-input-consents";
    }
}
