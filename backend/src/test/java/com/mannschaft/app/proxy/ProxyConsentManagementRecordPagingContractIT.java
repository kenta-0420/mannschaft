package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DBページング・同日時の順序・レコード件数に比例するクエリの不在を検証する。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ProxyConsentManagementRecordPagingContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ProxyInputConsentRepository consents;
    @Autowired private ProxyInputRecordRepository records;
    @PersistenceContext private EntityManager em;
    private ProxyConsentManagementTestFixture fixture;
    private Long organization;
    private Long admin;
    private Long subject;
    private Long proxy;
    private Long consentId;

    @BeforeEach
    void 準備() {
        fixture = new ProxyConsentManagementTestFixture(users, organizations, consents, records);
        organization = fixture.organization();
        admin = fixture.account();
        subject = fixture.account();
        proxy = fixture.account();
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", null, organization);
        consentId = fixture.consent(organization, subject, proxy).getId();
    }

    @Test
    void 同日時の履歴はid降順でページ跨ぎが安定() throws Exception {
        Long first = fixture.record(consentId, subject, proxy, ProxyInputRecordEntity.InputSource.PAPER_FORM);
        Long second = fixture.record(consentId, subject, proxy, ProxyInputRecordEntity.InputSource.IN_PERSON);
        em.flush();
        // 同時刻のfixtureをJPQLで明示し、DBの時刻精度や実行速度にテスト結果を依存させない。
        em.createQuery("update ProxyInputRecordEntity r set r.createdAt = :at where r.subjectUserId = :subject")
                .setParameter("at", LocalDateTime.of(2026, 1, 1, 12, 0))
                .setParameter("subject", subject).executeUpdate();
        em.clear();
        mvc.perform(get("/api/v1/proxy-input-records").with(user(subject.toString()))
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(second));
        mvc.perform(get("/api/v1/proxy-input-records").with(user(subject.toString()))
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(first));
    }

    @Test
    void DBページングで取得件数を制限しSQL数は履歴件数に比例しない() throws Exception {
        for (int index = 0; index < 50; index++) {
            fixture.record(consentId, subject, proxy, ProxyInputRecordEntity.InputSource.PAPER_FORM);
        }
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            mvc.perform(get("/api/v1/proxy-input-records").with(user(admin.toString()))
                            .param("organizationId", organization.toString()).param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.meta.total").value(50));
            long oneRowQueries = statistics.getPrepareStatementCount();
            assertThat(statistics.getEntityStatistics(ProxyInputRecordEntity.class.getName()).getLoadCount())
                    .isEqualTo(1);
            em.clear();
            statistics.clear();
            mvc.perform(get("/api/v1/proxy-input-records").with(user(admin.toString()))
                            .param("organizationId", organization.toString()).param("size", "100"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(50));
            assertThat(statistics.getEntityStatistics(ProxyInputRecordEntity.class.getName()).getLoadCount())
                    .isEqualTo(50);
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(oneRowQueries + 1);
        } finally {
            statistics.clear();
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }
}
