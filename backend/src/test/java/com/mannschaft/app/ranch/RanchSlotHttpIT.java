package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.entity.RanchCollectibleCatalogEntity;
import com.mannschaft.app.ranch.entity.RanchInventoryEntity;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 枠操作のresource guard、本人条件、保存済み204再送を実filter/MySQLで確認する。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchSlotHttpIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchRoomPlacementRepository slots;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchInventoryRepository inventory;
    @Autowired private RanchCollectibleCatalogRepository catalog;
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

    @Test
    void stateShowsOnlyActiveOwnedDecorationAndKeepsPlacementAckUnchanged() throws Exception {
        mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
        String key = "SAFE_" + UUID.randomUUID().toString().replace("-", "");
        String sku = "SKU_" + UUID.randomUUID().toString().replace("-", "");
        catalog.saveAndFlush(RanchCollectibleCatalogEntity.builder()
                .collectibleKey(key).labelKey("ranch.fixture.approved")
                .assetKey("finite-approved-fixture").sourceKind("SHOP").active(true)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        var item = inventory.saveAndFlush(RanchInventoryEntity.builder()
                .ownerId(owners.findByUserId(me).orElseThrow().getId()).userId(me)
                .skuKey(sku).collectibleKey(key).acquisitionKind("SHOP")
                .acquisitionKey(("SHOP:" + sku).getBytes(StandardCharsets.US_ASCII))
                .priceVersion(1L).awardedAt(Instant.now()).revoked(false).build());
        mvc.perform(put("/api/v1/me/ranch/room/slots/SHELF_1").with(user(me.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inventoryId\":\"" + item.getId() + "\",\"version\":\"0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decoration").doesNotExist());
        mvc.perform(get("/api/v1/me/ranch").with(user(me.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomSlots[0].decoration.collectibleKey").value(key))
                .andExpect(jsonPath("$.data.roomSlots[0].decoration.labelKey")
                        .value("ranch.fixture.approved"))
                .andExpect(jsonPath("$.data.roomSlots[0].decoration.assetKey")
                        .value("finite-approved-fixture"));
        item.revoke();
        inventory.saveAndFlush(item);
        mvc.perform(get("/api/v1/me/ranch").with(user(me.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomSlots[0].inventoryId").value(item.getId().toString()))
                .andExpect(jsonPath("$.data.roomSlots[0].decoration").doesNotExist());
    }
}
