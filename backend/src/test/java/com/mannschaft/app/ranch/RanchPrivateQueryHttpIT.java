package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 私有履歴の実filter/MySQLとCursorPagedResponseトップレベル契約を確認する。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchPrivateQueryHttpIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchCommandRepository commands;
    @Autowired private com.mannschaft.app.ranch.repository.RanchOperationalControlRepository controls;
    @Autowired private ObjectMapper json;
    private Long me;
    private Long other;

    @BeforeEach
    void createSyntheticUsers() {
        RanchTestFixture.operationalControl(controls);
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        other = users.saveAndFlush(RanchTestFixture.user()).getId();
    }

    /** RanchPrivateQueryController#records と RanchPrivateQueryController#collectibles の本人ページを実HTTPで確認する。 */
    @Test
    void recordsAndCollectiblesHaveOneTopLevelDataArrayAndMeta() throws Exception {
        for (String path : new String[] {"/api/v1/me/ranch/records", "/api/v1/me/ranch/collectibles"}) {
            var response = mvc.perform(get(path).with(user(me.toString())).param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.meta.limit").value(2))
                    .andReturn();
            var body = json.readTree(response.getResponse().getContentAsString());
            assertThat(body.path("data").size()).isZero();
            assertThat(body.path("data").isObject()).isFalse();
        }
    }

    /** RanchPrivateQueryController#shop の本人境界を含む私的履歴の匿名・変身拒否を確認する。 */
    @Test
    void privateHistoryRejectsAnonymousAndAdminImpersonation() throws Exception {
        mvc.perform(get("/api/v1/me/ranch/records"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/ranch/collectibles")
                        .with(user("999999999").roles("SYSTEM_ADMIN"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, me.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/ranch/shop")
                        .with(user(me.toString()).roles("MEMBER"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, other.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPageSizeDoesNotBecomeServerErrorOrExposeOtherUser() throws Exception {
        mvc.perform(get("/api/v1/me/ranch/records")
                        .with(user(me.toString())).param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/ranch/collectibles")
                        .with(user(other.toString())).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void commandResourceGuardHidesOtherPersonsCommandId() throws Exception {
        UUID key = UUID.randomUUID();
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        UUID commandId = commands.findByUserIdAndIdempotencyKey(me, key).orElseThrow().getId();
        mvc.perform(get("/api/v1/me/ranch/commands/{commandId}", commandId)
                        .with(user(other.toString())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/ranch/commands/{commandId}", UUID.randomUUID())
                        .with(user(other.toString())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me/ranch/commands/{commandId}", commandId)
                        .with(user(me.toString())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }
}
