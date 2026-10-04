package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 枠操作のresource guard、本人条件、保存済み204再送を実filter/MySQLで確認する。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchSlotHttpIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchRoomPlacementRepository slots;
    @Autowired private com.mannschaft.app.ranch.repository.RanchOperationalControlRepository controls;
    private Long me;
    private Long other;

    @BeforeEach
    void createSyntheticUsers() {
        RanchTestFixture.operationalControl(controls);
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        other = users.saveAndFlush(RanchTestFixture.user()).getId();
    }

    @Test
    void unenrolledOtherHasNoSlotAndWrongInventoryIsConcealed() throws Exception {
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/me/ranch/room/slots/SHELF_1")
                        .with(user(other.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "0"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/me/ranch/room/slots/SHELF_1")
                        .with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryId\":\"" + UUID.randomUUID() + "\",\"version\":\"0\"}"))
                .andExpect(status().isNotFound());
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3)
                .allSatisfy(slot -> assertThat(slot.getInventoryId()).isNull());
        assertThat(slots.findByUserIdOrderBySlotKey(other)).isEmpty();
    }

    @Test
    void emptySlotClearReturns204AndSameKeyReplayDoesNotAdvanceVersionTwice() throws Exception {
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        UUID key = UUID.randomUUID();
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(delete("/api/v1/me/ranch/room/slots/SHELF_1")
                            .with(user(me.toString()))
                            .header("Idempotency-Key", key)
                            .header("If-Match", "0"))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "private, no-store"));
        }
        assertThat(slots.findByUserIdAndSlotKey(me, "SHELF_1").orElseThrow().getVersion())
                .isEqualTo(1);
    }
}
