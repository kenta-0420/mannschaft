package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchParticipationPeriodRepository;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    @Autowired private RanchCommandRepository commands;
    @Autowired private RanchParticipationPeriodRepository periods;
    @Autowired private RanchPurgeService purge;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired @Qualifier("primaryDataSource") private ObjectProvider<DataSource> primary;
    @Value("${app.auth.operation-guard.max-concurrent:4}") private int configuredMaximum;
    private Long me;
    private Long other;

    @BeforeEach
    void createSyntheticUsers() {
        RanchTestFixture.operationalControl(controls);
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        other = users.saveAndFlush(RanchTestFixture.user()).getId();
    }

    @AfterEach
    void removeOnlyTheseSyntheticUsersAndRanchRows() {
        for (Long userId : new Long[] {me, other}) {
            if (userId == null) continue;
            purge.purgeUser(userId);
            for (String table : List.of("ranch_owners", "ranch_dinosaurs", "ranch_commands",
                    "ranch_point_ledger", "ranch_care_week_budgets", "ranch_affinity_units",
                    "ranch_room_placements", "ranch_collectible_inventory",
                    "ranch_participation_periods", "ranch_reward_decisions", "ranch_week_budgets")) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
                        Long.class, userId)).as("本人fixtureの残存行: %s", table).isZero();
            }
            users.deleteById(userId);
        }
    }

    @Test
    void concurrentDifferentKeyEnrollmentThroughAuthGuardCreatesOnlyOneEggAndFrozenSnapshot()
            throws Exception {
        DataSource selected = primary.getIfAvailable(() -> dataSource);
        assertThat(selected instanceof HikariDataSource).as("正規guardが使うHikari PRIMARY fixture").isTrue();
        int poolSize = ((HikariDataSource) selected).getMaximumPoolSize();
        int admissionLimit = Math.min(configuredMaximum, Math.max(1, (poolSize - 2) / 2));
        System.out.printf("AC02 synthetic fixture: primaryPoolSize=%d configuredMaximum=%d admissionLimit=%d%n",
                poolSize, configuredMaximum, admissionLimit);
        assertThat(admissionLimit).as("二要求を受付できる通常fixture（P2の503は正当拒否で別scope）")
                .isGreaterThanOrEqualTo(2);
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();
        // HTTP正規入口から独立thread/TXで送る。writer単独競合はauth lock前提を外すため使わない。
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        List<MvcResult> responses;
        try {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return enrollViaHttp(firstKey); });
            var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return enrollViaHttp(secondKey); });
            responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("開始競合試験workerが終了していません");
            }
        }
        // 受付capacity拒否503や予期しない409はこの基礎caseを未成功にする。guardを迂回しない。
        assertThat(responses.stream().map(result -> result.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(201, 200);
        var firstState = json.readTree(responses.get(0).getResponse().getContentAsString()).path("data");
        var secondState = json.readTree(responses.get(1).getResponse().getContentAsString()).path("data");
        assertThat(firstState.path("owner").path("id")).isEqualTo(secondState.path("owner").path("id"));
        assertThat(firstState.path("dinosaur").path("id")).isEqualTo(secondState.path("dinosaur").path("id"));
        assertThat(firstState.path("dinosaur").path("stage").asText()).isEqualTo("EGG");
        assertThat(secondState.path("dinosaur").path("stage").asText()).isEqualTo("EGG");
        assertThat(firstState.path("dinosaur").path("egg")).isEqualTo(secondState.path("dinosaur").path("egg"));
        var dinosaur = dinosaurs.findByUserId(me).orElseThrow();
        UUID dinosaurId = dinosaur.getId();
        String frozenEgg = dinosaur.getEggRuleSnapshot();
        String frozenGrowth = dinosaur.getGrowthRuleSnapshot();
        assertThat(dinosaur.getEggReadyAt()).isEqualTo(dinosaur.getEggStartedAt().plusSeconds(604800));
        assertThat(dinosaur.getXp()).isZero();
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isZero();
        assertThat(owners.findByUserId(me).orElseThrow().getBalance()).isZero();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3)
                .extracting(slot -> slot.getSlotKey()).containsExactly("SHELF_1", "SHELF_2", "SHELF_3");
        assertThat(slots.findByUserIdOrderBySlotKey(me)).allSatisfy(slot -> {
            assertThat(slot.getInventoryId()).isNull();
            assertThat(slot.getVersion()).isZero();
        });
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).hasSize(1);
        for (String table : List.of("ranch_owners", "ranch_dinosaurs", "ranch_participation_periods")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
                    Long.class, me)).as("同時開始後の本人行: %s", table).isEqualTo(1);
        }
        assertThat(commands.countByUserId(me)).isEqualTo(2);
        assertThat(commands.findByUserIdAndIdempotencyKey(me, firstKey)).isPresent();
        assertThat(commands.findByUserIdAndIdempotencyKey(me, secondKey)).isPresent();
        var keys = List.of(firstKey, secondKey);
        for (int index = 0; index < keys.size(); index++) {
            var retry = enrollViaHttp(keys.get(index));
            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(json.readTree(retry.getResponse().getContentAsString()).path("data"))
                    .isEqualTo(json.readTree(responses.get(index).getResponse().getContentAsString()).path("data"));
        }
        var afterReplay = dinosaurs.findByUserId(me).orElseThrow();
        assertThat(afterReplay.getId()).isEqualTo(dinosaurId);
        assertThat(afterReplay.getEggRuleSnapshot()).isEqualTo(frozenEgg);
        assertThat(afterReplay.getGrowthRuleSnapshot()).isEqualTo(frozenGrowth);
        assertThat(commands.countByUserId(me)).isEqualTo(2);
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3);
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).hasSize(1);
        assertThat(owners.findByUserId(other)).isEmpty();
        assertThat(dinosaurs.findByUserId(other)).isEmpty();
    }

    private MvcResult enrollViaHttp(UUID key) throws Exception {
        return mvc.perform(post("/api/v1/me/ranch").with(user(me.toString()))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
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
