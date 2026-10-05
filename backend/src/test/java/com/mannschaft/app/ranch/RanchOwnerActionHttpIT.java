package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.time.Instant;
import java.time.Clock;

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
@TestPropertySource(properties = {"mannschaft.ranch.development-fixtures=true", "mannschaft.ranch.development-visuals=true"})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchOwnerActionHttpIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private com.mannschaft.app.ranch.repository.RanchOperationalControlRepository controls;
    @Autowired private ObjectMapper json;
    @Autowired private Clock clock;
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

    /** 実HTTPで参加・選定・命名し、時間fixtureだけを早めて孵化と給餌の不変ACKを検証する。 */
    @Test
    void hatchAndFeedingHttpFreezeCommandsAndReplayWithoutCurrentVersion() throws Exception {
        enroll();
        String version = Long.toString(owners.findByUserId(me).orElseThrow().getVersion());
        mvc.perform(put("/api/v1/me/ranch/assignment").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"HABITAT_RANDOM\",\"habitat\":\"LAND\",\"version\":\"" + version + "\"}"))
                .andExpect(status().isOk());
        var egg = dinosaurs.findByUserId(me).orElseThrow();
        Instant now = Instant.now(clock);
        ReflectionTestUtils.setField(egg, "eggStartedAt", now.minusSeconds(604801));
        ReflectionTestUtils.setField(egg, "eggReadyAt", now.minusSeconds(1));
        dinosaurs.saveAndFlush(egg);
        version = Long.toString(owners.findByUserId(me).orElseThrow().getVersion());
        String hatchBody = "{\"version\":\"" + version + "\",\"name\":\"テスト\",\"nameConfirmed\":true}";
        UUID hatchKey = UUID.randomUUID();
        var hatched = mvc.perform(post("/api/v1/me/ranch/hatch").with(user(me.toString()))
                        .header("Idempotency-Key", hatchKey).contentType(MediaType.APPLICATION_JSON).content(hatchBody))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.kind").value("HATCH_RESULT"))
                .andExpect(jsonPath("$.data.result.name").value("テスト"))
                .andExpect(jsonPath("$.data.result.commandId").isNotEmpty()).andReturn();
        version = Long.toString(owners.findByUserId(me).orElseThrow().getVersion());
        String feedBody = "{\"version\":\"" + version + "\"}";
        UUID feedKey = UUID.randomUUID();
        var fed = mvc.perform(post("/api/v1/me/ranch/feeding").with(user(me.toString()))
                        .header("Idempotency-Key", feedKey).contentType(MediaType.APPLICATION_JSON).content(feedBody))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.costPoints").value("0")).andReturn();
        String commandId = json.readTree(fed.getResponse().getContentAsString()).path("data").path("commandId").textValue();
        assertThat(fed.getResponse().getHeader("Location")).isEqualTo("/api/v1/me/ranch/commands/" + commandId);
        var replayFeed = mvc.perform(post("/api/v1/me/ranch/feeding").with(user(me.toString()))
                        .header("Idempotency-Key", feedKey).contentType(MediaType.APPLICATION_JSON).content(feedBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replayFeed.getResponse().getContentAsString()).path("data"))
                .isEqualTo(json.readTree(fed.getResponse().getContentAsString()).path("data"));
        var replayHatch = mvc.perform(post("/api/v1/me/ranch/hatch").with(user(me.toString()))
                        .header("Idempotency-Key", hatchKey).contentType(MediaType.APPLICATION_JSON).content(hatchBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replayHatch.getResponse().getContentAsString()).path("data"))
                .isEqualTo(json.readTree(hatched.getResponse().getContentAsString()).path("data"));
        // 同じ認証主体で保存済みACKを再送しても、現在のusers行が凍結なら先に拒否する。
        var account = users.findById(me).orElseThrow(); account.freeze(); users.saveAndFlush(account);
        mvc.perform(post("/api/v1/me/ranch/hatch").with(user(me.toString()))
                        .header("Idempotency-Key", hatchKey).contentType(MediaType.APPLICATION_JSON).content(hatchBody))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "private, no-store"));
        mvc.perform(post("/api/v1/me/ranch/feeding").with(user(me.toString()))
                        .header("Idempotency-Key", feedKey).contentType(MediaType.APPLICATION_JSON).content(feedBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/ranch/purchases").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuKey\":\"synthetic-empty-shop\",\"priceVersion\":\"1\",\"version\":\"1\"}"))
                .andExpect(status().isForbidden());
        assertThat(owners.findByUserId(other)).isEmpty();
    }

    /** 空shopの購入入口と三つの私有操作の変身拒否を実filter経由で検証する。 */
    @Test
    void newActionRoutesRejectImpersonationAndEmptyShopCannotPurchase() throws Exception {
        enroll();
        String version = Long.toString(owners.findByUserId(me).orElseThrow().getVersion());
        var bodies = java.util.Map.of("hatch", "{\"version\":\"" + version + "\",\"name\":\"テスト\",\"nameConfirmed\":true}",
                "feeding", "{\"version\":\"" + version + "\"}",
                "purchases", "{\"skuKey\":\"synthetic-empty-shop\",\"priceVersion\":\"1\",\"version\":\"" + version + "\"}");
        for (var entry : bodies.entrySet()) {
            mvc.perform(post("/api/v1/me/ranch/" + entry.getKey())
                            .with(user("999999999").roles("SYSTEM_ADMIN"))
                            .header(AdminImpersonationFilter.HEADER_IMPERSONATE, me.toString())
                            .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(entry.getValue()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/me/ranch/purchases").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(bodies.get("purchases")))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "private, no-store"));
        assertThat(owners.findByUserId(other)).isEmpty();
    }
}
