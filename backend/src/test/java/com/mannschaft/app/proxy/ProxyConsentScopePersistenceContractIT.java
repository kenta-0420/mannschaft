package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V18.011のNOT NULL制約を専用MySQLに再現し、実機の前提登録を保証する。 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ProxyConsentScopePersistenceContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ProxyInputConsentRepository consents;
    @Autowired private ProxyInputRecordRepository records;
    @Autowired private RoleRepository roles;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    @Test
    void 正本の非nullFKでも同意書とscopeを一度で保存できる() throws Exception {
        var tx = new TransactionTemplate(transactionManager);
        var userIds = new ArrayList<Long>();
        var orgId = new Long[1];
        var roleId = new Long[1];
        boolean originalNullable = "YES".equals(jdbc.queryForObject(
                "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name='proxy_input_consent_scopes' AND column_name='proxy_input_consent_id'",
                String.class));
        try {
            // create-dropは既存nullable未指定を再現するため、正本DDLの制約だけ補う。
            if (originalNullable) jdbc.execute("ALTER TABLE proxy_input_consent_scopes "
                    + "MODIFY proxy_input_consent_id BIGINT NOT NULL");
            tx.executeWithoutResult(ignored -> {
                var fixture = new ProxyConsentManagementTestFixture(users, organizations, consents, records);
                orgId[0] = fixture.organization();
                for (int index = 0; index < 3; index++) userIds.add(fixture.account());
                boolean hadRole = roles.findByName("ADMIN").isPresent();
                MembershipTestHelper.insertUserRole(em, userIds.getFirst(), "ADMIN", null, orgId[0]);
                if (!hadRole) roleId[0] = roles.findByName("ADMIN").orElseThrow().getId();
            });
            mvc.perform(post("/api/v1/organizations/{org}/proxy-input-consents", orgId[0])
                            .with(user(userIds.getFirst().toString())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"subjectUserId":%d,"proxyUserId":%d,"consentMethod":"PAPER_SIGNED",
                                     "effectiveFrom":"%s","effectiveUntil":"%s","scopes":["SURVEY"]}
                                    """.formatted(userIds.get(1), userIds.get(2),
                                    LocalDate.now(), LocalDate.now().plusDays(30))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.scopes[0]").value("SURVEY"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM proxy_input_consent_scopes s "
                    + "JOIN proxy_input_consents c ON c.id=s.proxy_input_consent_id "
                    + "WHERE c.organization_id=? AND s.feature_scope='SURVEY'", Long.class, orgId[0])).isEqualTo(1L);
        } finally {
            tx.executeWithoutResult(ignored -> {
                if (orgId[0] != null) {
                    jdbc.update("DELETE s FROM proxy_input_consent_scopes s JOIN proxy_input_consents c "
                            + "ON c.id=s.proxy_input_consent_id WHERE c.organization_id=?", orgId[0]);
                    em.createQuery("delete ProxyInputConsentEntity c where c.organizationId=:org")
                            .setParameter("org", orgId[0]).executeUpdate();
                }
                if (!userIds.isEmpty()) {
                    em.createQuery("delete UserRoleEntity role where role.userId in :users")
                            .setParameter("users", userIds).executeUpdate();
                    users.deleteAllById(userIds);
                }
                if (orgId[0] != null) organizations.deleteById(orgId[0]);
                if (roleId[0] != null) roles.deleteById(roleId[0]);
            });
            if (originalNullable) jdbc.execute("ALTER TABLE proxy_input_consent_scopes "
                    + "MODIFY proxy_input_consent_id BIGINT NULL");
        }
    }
}
