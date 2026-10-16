package com.mannschaft.app.loyalty;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AC05/14/22/30のHTTP・実認可・実DB境界。Controllerの直呼びや内部Beanのmockは使わない。 */
@org.springframework.transaction.annotation.Transactional
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class LoyaltyHttpContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    private Long actorId;

    @BeforeEach
    void createActor() {
        UserEntity actor = LoyaltyTestFixture.user();
        actorId = users.saveAndFlush(actor).getId();
    }

    @Test
    void AC14_口座がなければ一覧は空でcursorメタを返す() throws Exception {
        mvc.perform(get("/api/v1/loyalty/accounts/me").with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.hasNext").value(false)).andExpect(jsonPath("$.meta.limit").value(20));
    }

    @Test
    void AC22_口座も所属もなくても本人申請一覧は空() throws Exception {
        mvc.perform(get("/api/v1/loyalty/award-requests/me").with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void AC14_未関係のチームへ口座直打ちしても存在情報は返さない() throws Exception {
        mvc.perform(get("/api/v1/teams/987654321/loyalty/account/me").with(user(actorId.toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LOYALTY_001"));
    }

    @Test
    void AC05_当該TEAM権限がない操作スタッフは403() throws Exception {
        mvc.perform(get("/api/v1/teams/987654321/loyalty/operation-catalog").with(user(actorId.toString())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("LOYALTY_002"));
    }

    @Test
    void AC05_未認証は401() throws Exception {
        mvc.perform(get("/api/v1/loyalty/accounts/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void AC14_limitは1から100まで() throws Exception {
        for (String limit : new String[]{"0", "101"}) {
            mvc.perform(get("/api/v1/loyalty/accounts/me").param("limit", limit).with(user(actorId.toString())))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }

    @Test
    void AC17_bootstrapの不正QRは情報を返さず400() throws Exception {
        mvc.perform(post("/api/v1/loyalty/self-award-bootstrap/me").with(user(actorId.toString()))
                        .contentType("application/json").content("{\"locationToken\":\"invalid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LOYALTY_010"));
    }
}
