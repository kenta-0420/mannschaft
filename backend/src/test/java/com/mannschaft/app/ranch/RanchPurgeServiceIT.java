package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ranch own-domain消去は本人11表だけを空にし、他人の卵/枠を保持する。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchPurgeServiceIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private RanchPurgeService purge;
    @Autowired private RanchOperationalControlRepository controls;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchRoomPlacementRepository slots;
    @MockitoSpyBean private JdbcTemplate jdbc;
    private Long me;
    private Long other;
    private final List<RanchGdprFixture.FixtureIds> masterRows = new ArrayList<>();
    private static final String[] OWNED_TABLES = {
            "ranch_point_ledger", "ranch_reward_decisions", "ranch_week_budgets",
            "ranch_affinity_units", "ranch_care_week_budgets", "ranch_room_placements",
            "ranch_collectible_inventory", "ranch_commands",
            "ranch_participation_periods", "ranch_dinosaurs", "ranch_owners"};

    private int count(String table, Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
                Integer.class, userId);
    }

    @BeforeEach
    void createSyntheticUsers() {
        RanchTestFixture.operationalControl(controls);
        masterRows.clear();
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        other = users.saveAndFlush(RanchTestFixture.user()).getId();
    }

    @AfterEach
    void removeOnlyOwnFixtureRows() {
        if (me != null) purge.purgeUser(me);
        if (other != null) purge.purgeUser(other);
        for (var row : masterRows) {
            jdbc.update("DELETE FROM ranch_reward_policies WHERE id = UUID_TO_BIN(?)",
                    row.policyId().toString());
            jdbc.update("DELETE FROM ranch_collectible_catalog WHERE collectible_key = ?",
                    row.collectibleKey());
        }
    }

    private void enroll(Long userId) throws Exception {
        mvc.perform(post("/api/v1/me/ranch").with(user(userId.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void deletesOwnRowsInOneTransactionAndRetryIsIdempotent() throws Exception {
        enroll(me);
        enroll(other);
        for (Long userId : new Long[] {me, other}) {
            masterRows.add(RanchGdprFixture.populate(jdbc, userId,
                    owners.findByUserId(userId).orElseThrow().getId(),
                    dinosaurs.findByUserId(userId).orElseThrow().getId()));
        }
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3);
        for (String table : OWNED_TABLES) {
            assertThat(count(table, me)).as("before me " + table).isPositive();
            assertThat(count(table, other)).as("before other " + table).isPositive();
        }
        purge.purgeUser(me);
        purge.purgeUser(me);
        assertThat(owners.findByUserId(me)).isEmpty();
        assertThat(dinosaurs.findByUserId(me)).isEmpty();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).isEmpty();
        for (String table : OWNED_TABLES) {
            assertThat(count(table, me)).as("after me " + table).isZero();
            assertThat(count(table, other)).as("after other " + table).isPositive();
        }
        assertThat(owners.findByUserId(other)).isPresent();
        assertThat(dinosaurs.findByUserId(other)).isPresent();
        assertThat(slots.findByUserIdOrderBySlotKey(other)).hasSize(3);
    }
    @Test
    void downstreamDeleteFailureRollsBackEarlierLedgerDeletion() throws Exception {
        enroll(me);
        masterRows.add(RanchGdprFixture.populate(jdbc, me,
                owners.findByUserId(me).orElseThrow().getId(),
                dinosaurs.findByUserId(me).orElseThrow().getId()));
        var before = new LinkedHashMap<String, Integer>();
        for (String table : OWNED_TABLES) {
            int rows = count(table, me);
            assertThat(rows).as("before rollback " + table).isPositive();
            before.put(table, rows);
        }
        var injected = new DataAccessResourceFailureException("自分の後段 DELETE の故障 fixture");
        // spy は自分の二番目の DELETE だけを差し替え、他の JDBC は実 DB に通す。
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(count("ranch_point_ledger", me)).as("故障直前の実 ledger DELETE").isZero();
            throw injected;
        }).when(jdbc).update("DELETE FROM ranch_reward_decisions WHERE user_id = ?", me);
        try {
            assertThatThrownBy(() -> purge.purgeUser(me)).isSameAs(injected);
            verify(jdbc).update("DELETE FROM ranch_point_ledger WHERE user_id = ?", me);
            for (String table : OWNED_TABLES) {
                assertThat(count(table, me)).as("rollback " + table).isEqualTo(before.get(table));
            }
        } finally {
            // 自動 reset の時機を待たず、既存 @AfterEach の実 purge より前に故障を外す。
            reset(jdbc);
        }
    }

}
