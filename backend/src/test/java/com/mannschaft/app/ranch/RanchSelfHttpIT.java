package com.mannschaft.app.ranch;

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
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AC02/08/26: 実Security filterとMySQLを通す本人牧場HTTP境界。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchSelfHttpIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchRoomPlacementRepository slots;
    private Long me;
    private Long other;

    @BeforeEach
    void createSyntheticUsers() {
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
