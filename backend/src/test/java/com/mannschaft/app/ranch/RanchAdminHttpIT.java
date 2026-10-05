package com.mannschaft.app.ranch;

import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.repository.UserRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC72: 実フィルタ・実MySQL・fresh admissionを通す管理入口。
 * MockMvcの認証principalは合成するが、DB資格・ACTIVE・管理handlerはmockしない。
 * JWT署名／ブラウザ／四源health閉束の証明とは別の試験である。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchAdminHttpIT extends AbstractMySqlIntegrationTest {
    private static final String HISTORY = "/api/v1/system-admin/ranch/care-rules";
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    private TransactionTemplate transaction;
    private Long administrator;
    private Long ordinary;

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
    void removeOnlyOwnFixtures() {
        if (transaction == null || administrator == null || ordinary == null) return;
        transaction.executeWithoutResult(ignored -> {
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
