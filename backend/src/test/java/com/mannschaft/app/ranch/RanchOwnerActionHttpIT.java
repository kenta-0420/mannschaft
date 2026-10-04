package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 本人設定・参加期間・TOUCHの実filterと保存済み応答境界。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchOwnerActionHttpIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchOwnerRepository owners;
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

    private void enroll() throws Exception {
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    /** RanchOwnerActionController#settings の本人保存と他人owner非作成を実HTTPで確認する。 */
    @Test
    void settingsSavedReplayAndVisibleProjectionRemainSelfScoped() throws Exception {
        enroll();
        String version = Long.toString(owners.findByUserId(me).orElseThrow().getVersion());
        UUID key = UUID.randomUUID();
        String body = "{\"renderStyle\":\"PIXEL\",\"motionMode\":\"REDUCED\","
                + "\"isSoundEnabled\":false,\"soundVolume\":37,\"version\":\"" + version + "\"}";
        var first = mvc.perform(put("/api/v1/me/ranch/settings").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.soundVolume").value(37)).andReturn();
        var replay = mvc.perform(put("/api/v1/me/ranch/settings").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("data"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).path("data"));
        assertThat(owners.findByUserId(other)).isEmpty();
    }

    /** RanchOwnerActionController#touch の本人結果と管理者変身による他人操作拒否を確認する。 */
    @Test
    void touchEggCanGainAffinityAndPrivateRoutesRejectImpersonation() throws Exception {
        enroll();
        String version = Long.toString(owners.findByUserId(me).orElseThrow().getVersion());
        UUID key = UUID.randomUUID();
        String body = "{\"kind\":\"TOUCH\",\"version\":\"" + version + "\"}";
        var firstTouch = mvc.perform(post("/api/v1/me/ranch/interactions").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reactionKey").value("EGG_TOUCH"))
                .andExpect(jsonPath("$.data.affinityChanged").value(true)).andReturn();
        var replayTouch = mvc.perform(post("/api/v1/me/ranch/interactions").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replayTouch.getResponse().getContentAsString()).path("data"))
                .isEqualTo(json.readTree(firstTouch.getResponse().getContentAsString()).path("data"));
        mvc.perform(post("/api/v1/me/ranch/pause")
                        .with(user("999999999").roles("SYSTEM_ADMIN"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, me.toString())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"" + version + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/ranch").with(user(me.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.owner.status").value("ACTIVE"));
    }
}
