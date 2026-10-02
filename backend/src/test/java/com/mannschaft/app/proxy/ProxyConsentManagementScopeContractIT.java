package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260820-1018: ProxyInputConsentController#getProxyInputRecords の実DB認可契約。
 * 認証フィルターを有効にし、組合と本人の交差・実操作レコード・ページングを検証する。
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("代理同意管理の実操作履歴・認可契約")
class ProxyConsentManagementScopeContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ProxyInputConsentRepository consents;
    @Autowired private ProxyInputRecordRepository records;
    @PersistenceContext private EntityManager em;
    private Long organizationA;
    private Long organizationB;
    private Long admin;
    private Long subject;
    private Long otherSubject;
    private Long proxy;
    private Long recordA;

    @BeforeEach
    void 準備() {
        var fixture = new ProxyConsentManagementTestFixture(users, organizations, consents, records);
        organizationA = fixture.organization();
        organizationB = fixture.organization();
        admin = fixture.account();
        subject = fixture.account();
        otherSubject = fixture.account();
        proxy = fixture.account();
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", null, organizationA);
        ProxyInputConsentEntity consentA = fixture.consent(organizationA, subject, proxy);
        ProxyInputConsentEntity consentB = fixture.consent(organizationB, subject, proxy);
        ProxyInputConsentEntity otherConsent = fixture.consent(organizationA, otherSubject, proxy);
        recordA = fixture.record(consentA.getId(), subject, proxy, ProxyInputRecordEntity.InputSource.PAPER_FORM);
        fixture.record(consentB.getId(), subject, proxy, ProxyInputRecordEntity.InputSource.PHONE_INTERVIEW);
        fixture.record(otherConsent.getId(), otherSubject, proxy, ProxyInputRecordEntity.InputSource.IN_PERSON);
        fixture.record(null, subject, proxy, ProxyInputRecordEntity.InputSource.GUARDIANSHIP_SWITCH);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("組合と本人をANDで絞り、同意書ではなく保存済み操作履歴を返す")
    void 組合と本人の交差() throws Exception {
        mvc.perform(get("/api/v1/proxy-input-records")
                        .with(user(admin.toString()))
                        .param("organizationId", organizationA.toString())
                        .param("subjectUserId", subject.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(recordA))
                .andExpect(jsonPath("$.data[0].targetEntityType").value("SURVEY_RESPONSE"))
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    @DisplayName("本人履歴は組合横断と同意書なしの後見切替を含み他人を含まない")
    void 本人履歴() throws Exception {
        mvc.perform(get("/api/v1/proxy-input-records").with(user(subject.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].consentId").isEmpty())
                .andExpect(jsonPath("$.data[0].inputSource").value("GUARDIANSHIP_SWITCH"));
    }

    @Test
    @DisplayName("未認証の履歴取得を401で拒否する")
    void 未認証() throws Exception {
        mvc.perform(get("/api/v1/proxy-input-records")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("本人でも組合一覧指定は管理資格がなければ403")
    void 非管理者の組合指定() throws Exception {
        mvc.perform(get("/api/v1/proxy-input-records").with(user(subject.toString()))
                        .param("organizationId", organizationA.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("他組合の管理資格では403")
    void 別組合() throws Exception {
        mvc.perform(get("/api/v1/proxy-input-records").with(user(admin.toString()))
                        .param("organizationId", organizationB.toString())
                        .param("subjectUserId", admin.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("組合なしで他人指定は403")
    void 他人指定() throws Exception {
        mvc.perform(get("/api/v1/proxy-input-records").with(user(admin.toString()))
                        .param("subjectUserId", subject.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("最終1件と範囲外ページは標準metaと空配列で返す")
    void ページ境界() throws Exception {
        mvc.perform(get("/api/v1/proxy-input-records").with(user(subject.toString()))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        mvc.perform(get("/api/v1/proxy-input-records").with(user(subject.toString()))
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

}
