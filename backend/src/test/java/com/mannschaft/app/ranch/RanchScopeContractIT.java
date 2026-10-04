package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AC02/08/26/58/61/73: 共有MySQLでHTTP経由の本人契約を固定する。
 * RanchController#state、RanchController#enroll、RanchController#settings、
 * RanchController#pause、RanchController#resume、RanchController#assignment、
 * RanchController#hatch、RanchController#feeding、RanchController#interaction。
 * 開発fixture有効化が他のITへ漏れないよう、独立プロパティのcontextを使う。
 */
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchScopeContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private com.mannschaft.app.ranch.repository.RanchOperationalControlRepository controls;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchRoomPlacementRepository slots;
    private Long userId;

    @BeforeEach
    void 本人無料未所属fixtureを作る() {
        RanchTestFixture.operationalControl(controls);
        userId = users.saveAndFlush(RanchTestFixture.user()).getId();
        authenticate(userId);
    }

    @AfterEach
    void 認証を解除する() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 未参加GETはnull状態でowner個体slotを作らない() throws Exception {
        mvc.perform(get("/api/v1/me/ranch"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.owner").doesNotExist())
                .andExpect(jsonPath("$.data.dinosaur").doesNotExist())
                .andExpect(jsonPath("$.data.roomSlots").isEmpty());
        assertThat(owners.findByUserId(userId)).isEmpty();
        assertThat(dinosaurs.findByUserId(userId)).isEmpty();
    }

    @Test
    void 開始は一頭と三空slotを原子保存し同keyは同snapshotを返す() throws Exception {
        UUID command = UUID.randomUUID();
        String first = mvc.perform(post("/api/v1/me/ranch")
                        .header("Idempotency-Key", command).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/me/ranch"))
                .andExpect(jsonPath("$.data.dinosaur.stage").value("EGG"))
                .andExpect(jsonPath("$.data.owner.balance").value("0"))
                .andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(post("/api/v1/me/ranch")
                        .header("Idempotency-Key", command).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(retry).isEqualTo(first);
        assertThat(dinosaurs.findByUserId(userId)).isPresent();
        assertThat(slots.findByUserIdOrderBySlotKey(userId)).hasSize(3)
                .allSatisfy(slot -> assertThat(slot.getInventoryId()).isNull());
    }

    @Test
    void 量や所有者をbodyから偽装すると400で未参加維持() throws Exception {
        mvc.perform(post("/api/v1/me/ranch").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1,\"xp\":999}"))
                .andExpect(status().isBadRequest());
        assertThat(owners.findByUserId(userId)).isEmpty();
    }

    @Test
    void UUID以外のcommandは400で保存しない() throws Exception {
        mvc.perform(post("/api/v1/me/ranch").header("Idempotency-Key", "abc")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(owners.findByUserId(userId)).isEmpty();
    }

    @Test
    void 未認証は401() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/me/ranch")).andExpect(status().isUnauthorized());
    }


    @Test
    void 描画切替は同個体と零残高を維持し再送は古い版でも元結果() throws Exception {
        enroll();
        UUID dinosaurId = dinosaurs.findByUserId(userId).orElseThrow().getId();
        String version = ownerVersion();
        UUID key = UUID.randomUUID();
        String body = "{\"renderStyle\":\"PAINT_2D\",\"motionMode\":\"REDUCED\",\"isSoundEnabled\":false,\"soundVolume\":50,\"version\":\"" + version + "\"}";
        String first = mvc.perform(put("/api/v1/me/ranch/settings").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.renderStyle").value("PAINT_2D"))
                .andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(put("/api/v1/me/ranch/settings").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(retry).isEqualTo(first);
        assertThat(dinosaurs.findByUserId(userId).orElseThrow().getId()).isEqualTo(dinosaurId);
        assertThat(owners.findByUserId(userId).orElseThrow().getBalance()).isZero();
        mvc.perform(put("/api/v1/me/ranch/settings").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("PAINT_2D", "PIXEL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RANCH_003"));
    }

    /** RanchOwnerActionController#pause と RanchOwnerActionController#resume の本人参加期間を実保存で確認する。 */
    @Test
    void 休止再開は同じ個体を保持しowner版だけを進める() throws Exception {
        enroll();
        UUID dinosaurId = dinosaurs.findByUserId(userId).orElseThrow().getId();
        mvc.perform(post("/api/v1/me/ranch/pause").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(versionBody()))
                .andExpect(status().isOk());
        assertThat(owners.findByUserId(userId).orElseThrow().getStatus()).isEqualTo(ParticipationStatus.PAUSED);
        mvc.perform(post("/api/v1/me/ranch/resume").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(versionBody()))
                .andExpect(status().isOk());
        assertThat(owners.findByUserId(userId).orElseThrow().getStatus()).isEqualTo(ParticipationStatus.ACTIVE);
        assertThat(dinosaurs.findByUserId(userId).orElseThrow().getId()).isEqualTo(dinosaurId);
    }

    @Test
    void 卵給餌は409でXPを作らず早期孵化もしない() throws Exception {
        enroll();
        mvc.perform(post("/api/v1/me/ranch/feeding").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(versionBody()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/me/ranch/hatch").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"" + ownerVersion() + "\",\"name\":\"たまご\",\"nameConfirmed\":true}"))
                .andExpect(status().isConflict());
        var dinosaur = dinosaurs.findByUserId(userId).orElseThrow();
        assertThat(dinosaur.getXp()).isZero();
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.EGG);
        assertThat(dinosaur.getName()).isNull();
    }

    @Test
    void ランダム出生選定はサーバーが固定しGETで孵化しない() throws Exception {
        enroll();
        UUID key = UUID.randomUUID();
        String body = "{\"method\":\"HABITAT_RANDOM\",\"habitat\":\"LAND\",\"version\":\"" + ownerVersion() + "\"}";
        String first = mvc.perform(put("/api/v1/me/ranch/assignment").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.habitat").value("LAND"))
                .andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(put("/api/v1/me/ranch/assignment").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(retry).isEqualTo(first);
        mvc.perform(get("/api/v1/me/ranch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignment.selectionConfirmed").value(true))
                .andExpect(jsonPath("$.data.dinosaur.stage").value("EGG"))
                .andExpect(jsonPath("$.data.dinosaur.egg.hatchReady").value(false));
    }

    @Test
    void 卵ふれあいは反応してもXPを増やさない() throws Exception {
        enroll();
        mvc.perform(post("/api/v1/me/ranch/interactions").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TOUCH\",\"version\":\"" + ownerVersion() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reactionKey").isString());
        assertThat(dinosaurs.findByUserId(userId).orElseThrow().getXp()).isZero();
    }

    @Test
    void command不在は404で入力UUIDを返さない() throws Exception {
        UUID absent = UUID.randomUUID();
        String response = mvc.perform(get("/api/v1/me/ranch/commands/" + absent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RANCH_001"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(absent.toString());
    }

    private void enroll() throws Exception {
        mvc.perform(post("/api/v1/me/ranch").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    private String ownerVersion() {
        return Long.toString(owners.findByUserId(userId).orElseThrow().getVersion());
    }

    private String versionBody() {
        return "{\"version\":\"" + ownerVersion() + "\"}";
    }

    static void authenticate(Long id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
    }
}
