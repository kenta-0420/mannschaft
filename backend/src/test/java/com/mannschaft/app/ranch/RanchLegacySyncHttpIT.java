package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本人明示取込入口の実filter・保存再送・未参加/管理者変身境界。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchLegacySyncHttpIT extends AbstractMySqlIntegrationTest {
    private static final String SYNC = "/api/v1/me/ranch/collectibles/sync";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RanchOwnerRepository owners;
    @Autowired com.mannschaft.app.ranch.repository.RanchOperationalControlRepository controls;
    @Autowired ObjectMapper json;

    @BeforeEach
    void seedOperationalControl() {
        RanchTestFixture.operationalControl(controls);
    }

    /** RanchLegacySyncController#sync の本人操作と未参加他人owner非作成を実HTTPで確認する。 */
    @Test
    void privateSyncRequiresSelfAndDoesNotEnrollOtherUser() throws Exception {
        Long me = users.saveAndFlush(RanchTestFixture.user()).getId();
        Long other = users.saveAndFlush(RanchTestFixture.user()).getId();
        mvc.perform(post(SYNC).header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"afterAwardId\":\"0\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(SYNC).with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"afterAwardId\":\"0\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        String admin = "999999999";
        mvc.perform(post(SYNC).with(user(admin).roles("SYSTEM_ADMIN"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, me.toString())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"afterAwardId\":\"0\"}"))
                .andExpect(status().isForbidden());

        UUID key = UUID.randomUUID();
        var first = mvc.perform(post(SYNC).with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"afterAwardId\":\"0\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.processedCount").value(0))
                .andExpect(jsonPath("$.data.nextAfterAwardId").value("0"))
                .andReturn();
        var replay = mvc.perform(post(SYNC).with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"afterAwardId\":\"0\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("data"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).path("data"));
        mvc.perform(post(SYNC).with(user(other.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"afterAwardId\":\"0\"}"))
                .andExpect(status().isNotFound());
        assertThat(owners.findByUserId(other)).isEmpty();
    }
}
