package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC02/08/26: 実Security filterとMySQLを通す本人牧場HTTP境界。
 * RanchSelfController#read と RanchSelfController#enroll の自己スコープ契約を固定する。
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchSelfHttpIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ObjectMapper json;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private com.mannschaft.app.ranch.repository.RanchOperationalControlRepository controls;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchRoomPlacementRepository slots;
    private Long me;
    private Long other;

    @BeforeEach
    void createSyntheticUsers() {
        RanchTestFixture.operationalControl(controls);
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        other = users.saveAndFlush(RanchTestFixture.user()).getId();
    }

    @Test
    void anonymousCannotReadPrivateRanch() throws Exception {
        mvc.perform(get("/api/v1/me/ranch"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedGetOfUnenrolledUserIsReadOnly() throws Exception {
        for (int index = 0; index < 2; index++) {
            mvc.perform(get("/api/v1/me/ranch").with(user(me.toString())))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(jsonPath("$.data.owner").doesNotExist())
                    .andExpect(jsonPath("$.data.dinosaur").doesNotExist());
        }
        assertThat(owners.findByUserId(me)).isEmpty();
        assertThat(dinosaurs.findByUserId(me)).isEmpty();
    }

    @Test
    void enrollingOneUserCreatesOneEggAndThreeEmptySlotsOnlyForSelf() throws Exception {
        UUID key = UUID.randomUUID();
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dinosaur.stage").value("EGG"));
        assertThat(owners.findByUserId(me)).isPresent();
        assertThat(dinosaurs.findByUserId(me)).isPresent();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3)
                .allSatisfy(slot -> assertThat(slot.getInventoryId()).isNull());
        mvc.perform(get("/api/v1/me/ranch").with(user(other.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.owner").doesNotExist())
                .andExpect(jsonPath("$.data.dinosaur").doesNotExist());
        assertThat(owners.findByUserId(other)).isEmpty();
    }

    @Test
    void savedEnrollmentReplayKeepsInitialSnapshotAfterOwnerSettingChanges() throws Exception {
        UUID key = UUID.randomUUID();
        var first = mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        var initial = json.readTree(first.getResponse().getContentAsString()).get("data");
        var owner = owners.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(owner, "soundVolume", 37);
        owners.saveAndFlush(owner);

        var replay = mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).get("data"))
                .isEqualTo(initial);
        mvc.perform(get("/api/v1/me/ranch").with(user(me.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settings.soundVolume").value(37));
        assertThat(owners.findByUserId(me).orElseThrow().getSoundVolume()).isEqualTo(37);
        assertThat(dinosaurs.findByUserId(me)).isPresent();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3);
    }

    @Test
    void adminImpersonationCannotReadOrEnrollButOwnAdminSessionCanRead() throws Exception {
        String admin = "999999999";
        mvc.perform(get("/api/v1/me/ranch").with(user(admin).roles("SYSTEM_ADMIN"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, me.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/ranch").with(user(admin).roles("SYSTEM_ADMIN"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, me.toString())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/ranch").with(user(me.toString()).roles("SYSTEM_ADMIN")))
                .andExpect(status().isOk());
        assertThat(owners.findByUserId(me)).isEmpty();
    }

    @Test
    void forgedImpersonationHeaderCannotReadOrEnrollAsMember() throws Exception {
        mvc.perform(get("/api/v1/me/ranch").with(user(me.toString()).roles("MEMBER"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, other.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()).roles("MEMBER"))
                        .header(AdminImpersonationFilter.HEADER_IMPERSONATE, other.toString())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/ranch").with(user(me.toString()).roles("MEMBER")))
                .andExpect(status().isOk());
        assertThat(owners.findByUserId(me)).isEmpty();
        assertThat(owners.findByUserId(other)).isEmpty();
    }

    @Test
    void clientCannotChooseOwnerOrXp() throws Exception {
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + other + ",\"xp\":999}"))
                .andExpect(status().isBadRequest());
        assertThat(owners.findByUserId(me)).isEmpty();
        assertThat(owners.findByUserId(other)).isEmpty();
    }
}
