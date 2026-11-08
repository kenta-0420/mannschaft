package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchInventoryQueryReader;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchRecordCursorCodec;
import com.mannschaft.app.ranch.service.RanchRecordQueryReader;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** AC68: 本人records/inventoryの件数境界、同MICROS keyset、一定SQL数を実MySQLで検証する。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchReadKeysetIT extends AbstractMySqlIntegrationTest {
    private static final Instant AT = Instant.parse("2026-10-07T02:00:00.123456Z");
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    private static final List<String> OWNED_TABLES = List.of(
            "ranch_point_ledger", "ranch_reward_decisions", "ranch_week_budgets",
            "ranch_affinity_units", "ranch_care_week_budgets", "ranch_room_placements",
            "ranch_collectible_inventory", "ranch_commands", "ranch_participation_periods",
            "ranch_dinosaurs", "ranch_owners");

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchPointLedgerRepository ledger;
    @Autowired private RanchRewardDecisionRepository decisions;
    @Autowired private RanchInventoryRepository inventory;
    @Autowired private RanchCollectibleCatalogRepository collectibles;
    @Autowired private RanchRecordQueryReader recordsReader;
    @Autowired private RanchInventoryQueryReader inventoryReader;
    @Autowired private RanchRecordCursorCodec cursors;
    @Autowired private RanchPurgeService purge;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagers;
    private final List<Long> syntheticUsers = new ArrayList<>();
    private final List<String> syntheticCatalogKeys = new ArrayList<>();

    private Fixture seed(int count) throws Exception {
        Long userId = users.saveAndFlush(RanchTestFixture.user()).getId();
        syntheticUsers.add(userId);
        var owner = enrollment.enroll(userId, UUID.randomUUID(), AT, PROJECTION);
        // 正の共通上位ビットで、UUID比較とMySQLのBINARY(16)降順を一致させる。
        long recordPrefix = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        long inventoryPrefix = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        List<UUID> recordIds = new ArrayList<>();
        List<UUID> inventoryIds = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID recordId = new UUID(recordPrefix, index + 1L);
            UUID inventoryId = new UUID(inventoryPrefix, index + 1L);
            var rows = RanchReadKeysetTestFixture.rows(userId, owner, recordId, inventoryId, index, AT);
            syntheticCatalogKeys.add(rows.catalog().getCollectibleKey());
            decisions.save(rows.decision());
            ledger.save(rows.record());
            collectibles.save(rows.catalog());
            inventory.save(rows.inventory());
            recordIds.add(recordId);
            inventoryIds.add(inventoryId);
        }
        decisions.flush();
        ledger.flush();
        collectibles.flush();
        inventory.flush();
        return new Fixture(userId, recordIds.stream().sorted(Comparator.reverseOrder()).toList(),
                inventoryIds.stream().sorted(Comparator.reverseOrder()).toList());
    }

    @AfterEach
    void removeOnlySyntheticRows() {
        for (Long id : syntheticUsers) {
            purge.purgeUser(id);
            for (String table : OWNED_TABLES) {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
                        Long.class, id)).as("本人fixtureの残存行: %s", table).isZero();
            }
            users.deleteById(id);
        }
        for (String key : syntheticCatalogKeys) {
            collectibles.deleteById(key);
            assertThat(collectibles.findById(key)).isEmpty();
        }
        syntheticUsers.clear();
        syntheticCatalogKeys.clear();
    }

    @Test
    void zeroOneHundredAndHundredOneRowsKeepBothKeysetsExactAndOtherOwnerUnchanged() throws Exception {
        var other = seed(3);
        var otherBefore = ownedRows(other.userId());
        for (int count : new int[] {0, 1, 100, 101}) {
            var fixture = seed(count);
            var before = ownedRows(fixture.userId());
            for (int limit : new int[] {1, 100}) {
                List<UUID> seenRecords = new ArrayList<>();
                List<UUID> seenInventory = new ArrayList<>();
                String recordCursor = null;
                String inventoryCursor = null;
                int pages = Math.max(1, (count + limit - 1) / limit);
                for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
                    int expectedSize = Math.min(limit, count - seenRecords.size());
                    var records = recordsReader.page(fixture.userId(), recordCursor, limit);
                    var items = inventoryReader.page(fixture.userId(), inventoryCursor, limit);
                    assertThat(records.getData()).hasSize(expectedSize);
                    assertThat(items.getData()).hasSize(expectedSize);
                    for (var row : records.getData()) {
                        seenRecords.add(row.id());
                        assertThat(row.occurredAt()).isEqualTo(AT);
                        assertThat(row.sourceType()).isEqualTo("ATTENDANCE_RESPONSE");
                    }
                    for (var row : items.getData()) {
                        seenInventory.add(row.id());
                        assertThat(row.awardedAt()).isEqualTo(AT);
                    }
                    recordCursor = records.getMeta().getNextCursor();
                    inventoryCursor = items.getMeta().getNextCursor();
                    boolean more = seenRecords.size() < count;
                    assertThat(records.getMeta().isHasNext()).isEqualTo(more);
                    assertThat(items.getMeta().isHasNext()).isEqualTo(more);
                    if (more) {
                        assertThat(recordCursor).isNotBlank();
                        assertThat(inventoryCursor).isNotBlank();
                        assertThat(cursors.decode(fixture.userId(), recordCursor).occurredAt()).isEqualTo(AT);
                        assertThat(cursors.decode(fixture.userId(), recordCursor).id()).isEqualTo(seenRecords.getLast());
                        assertThat(cursors.decodeInventory(fixture.userId(), inventoryCursor).occurredAt()).isEqualTo(AT);
                        assertThat(cursors.decodeInventory(fixture.userId(), inventoryCursor).id()).isEqualTo(seenInventory.getLast());
                    } else {
                        assertThat(recordCursor).isNull();
                        assertThat(inventoryCursor).isNull();
                    }
                }
                assertThat(seenRecords).containsExactlyElementsOf(fixture.recordIds()).doesNotHaveDuplicates();
                assertThat(seenInventory).containsExactlyElementsOf(fixture.inventoryIds()).doesNotHaveDuplicates();
            }
            assertThat(ownedRows(fixture.userId())).usingRecursiveComparison().isEqualTo(before);
            assertThat(ownedRows(other.userId())).usingRecursiveComparison().isEqualTo(otherBefore);
        }
        assertThat(recordsReader.page(other.userId(), null, 100).getData().stream().map(row -> row.id()).toList())
                .containsExactlyElementsOf(other.recordIds());
        assertThat(inventoryReader.page(other.userId(), null, 100).getData().stream().map(row -> row.id()).toList())
                .containsExactlyElementsOf(other.inventoryIds());
    }

    @Test
    void boundedQueriesAndLimitPlusOneLoadsDoNotGrowWithEitherReaderRowCount() throws Exception {
        var statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            for (int count : new int[] {0, 1, 100, 101}) {
                var fixture = seed(count);
                for (int limit : new int[] {1, 100}) {
                    statistics.clear();
                    var records = recordsReader.page(fixture.userId(), null, limit);
                    assertQueries(statistics, count == 0 ? 1 : 2,
                            Math.min(count, limit + 1) + Math.min(count, limit));
                    assertThat(records.getData()).hasSize(Math.min(count, limit));
                    if (records.getMeta().getNextCursor() != null) {
                        statistics.clear();
                        recordsReader.page(fixture.userId(), records.getMeta().getNextCursor(), limit);
                        assertQueries(statistics, 2,
                                Math.min(count - limit, limit + 1) + Math.min(count - limit, limit));
                    }
                    statistics.clear();
                    var items = inventoryReader.page(fixture.userId(), null, limit);
                    assertQueries(statistics, count == 0 ? 2 : 3,
                            Math.min(count, limit + 1) + 3 + Math.min(count, limit));
                    assertThat(items.getData()).hasSize(Math.min(count, limit));
                    if (items.getMeta().getNextCursor() != null) {
                        statistics.clear();
                        inventoryReader.page(fixture.userId(), items.getMeta().getNextCursor(), limit);
                        assertQueries(statistics, 3,
                                Math.min(count - limit, limit + 1) + 3 + Math.min(count - limit, limit));
                    }
                }
            }
        } finally {
            statistics.setStatisticsEnabled(originallyEnabled);
        }
    }

    private void assertQueries(Statistics statistics, long queryCount, long loadedRows) {
        assertThat(statistics.getQueryExecutionCount()).as("まとめて参照するHQL数").isEqualTo(queryCount);
        assertThat(statistics.getPrepareStatementCount()).as("件数に依存しないSQL数").isEqualTo(queryCount);
        assertThat(statistics.getEntityLoadCount()).as("limit+1とページ内関連行だけを取得").isEqualTo(loadedRows);
    }

    private Map<String, List<Map<String, Object>>> ownedRows(Long id) {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : OWNED_TABLES) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE user_id = ? ORDER BY id", id));
        }
        return rows;
    }

    private record Fixture(Long userId, List<UUID> recordIds, List<UUID> inventoryIds) { }
}
