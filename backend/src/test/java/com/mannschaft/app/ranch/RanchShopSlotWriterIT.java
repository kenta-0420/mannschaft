package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchPurchaseRequest;
import com.mannschaft.app.ranch.dto.RanchPurchaseResult;
import com.mannschaft.app.ranch.dto.RanchSlotRequest;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import com.mannschaft.app.ranch.repository.RanchShopCatalogRepository;
import com.mannschaft.app.ranch.entity.RanchShopCatalogEntity;
import com.mannschaft.app.ranch.entity.RanchCollectibleCatalogEntity;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchInventoryQueryReader;
import com.mannschaft.app.ranch.service.RanchPurchaseWriter;
import com.mannschaft.app.ranch.service.RanchShopQueryReader;
import com.mannschaft.app.ranch.service.RanchSlotWriter;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 承認済み開発SKUの購入、本人所有、枠操作、再送を実MySQLで検証する。 */
@TestPropertySource(properties = {
        "mannschaft.ranch.development-fixtures=true",
        "mannschaft.ranch.shop.development-fixtures=true"
})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchShopSlotWriterIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T07:00:00.123456Z");
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchInventoryRepository inventory;
    @Autowired private RanchShopCatalogRepository catalog;
    @Autowired private RanchCollectibleCatalogRepository collectibles;
    @Autowired private RanchRoomPlacementRepository placements;
    @Autowired private RanchPointLedgerRepository ledger;
    @Autowired private RanchCommandRepository commands;
    @Autowired private RanchPurchaseWriter purchases;
    @Autowired private RanchSlotWriter slots;
    @Autowired private RanchInventoryQueryReader inventoryReader;
    @Autowired private RanchShopQueryReader shopReader;
    @Autowired private RanchPurgeService purge;
    @Autowired private JdbcTemplate jdbc;
    private Long me;
    private String sku;
    private final List<Long> syntheticUsers = new ArrayList<>();
    private final List<String> syntheticSkuKeys = new ArrayList<>();

    @BeforeEach
    void seedOwnerAndPublishedSku() {
        me = createSyntheticUser();
        sku = "DEV-SHELF-" + UUID.randomUUID();
        enrollment.enroll(me, UUID.randomUUID(), NOW.minusSeconds(10), PROJECTION);
        var owner = owners.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(owner, "balance", 100L);
        owners.saveAndFlush(owner);
        seedPublishedSku(sku);
    }

    private Long createSyntheticUser() {
        Long id = users.saveAndFlush(RanchTestFixture.user()).getId();
        syntheticUsers.add(id);
        return id;
    }

    private void seedPublishedSku(String key) {
        syntheticSkuKeys.add(key);
        collectibles.saveAndFlush(RanchCollectibleCatalogEntity.builder()
                .collectibleKey(key).labelKey("ranch.dev.shelf1")
                .assetKey("dev-shelf-1").sourceKind("SHOP").active(true)
                .createdAt(NOW.minusSeconds(1)).updatedAt(NOW.minusSeconds(1)).build());
        catalog.saveAndFlush(RanchShopCatalogEntity.builder()
                .skuKey(key).collectibleKey(key)
                .pricePoints(20).priceVersion(1).active(true)
                .createdAt(NOW.minusSeconds(1)).build());
    }

    @AfterEach
    void removeOnlyThisTestsSyntheticOwnersAndCatalog() {
        for (Long userId : syntheticUsers) {
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
        for (String key : syntheticSkuKeys) {
            catalog.findFirstBySkuKeyAndActiveTrueOrderByPriceVersionDesc(key)
                    .ifPresent(row -> catalog.deleteById(row.getId()));
            collectibles.deleteById(key);
            assertThat(catalog.findFirstBySkuKeyAndActiveTrueOrderByPriceVersionDesc(key)).isEmpty();
            assertThat(collectibles.findById(key)).isEmpty();
        }
        syntheticUsers.clear();
        syntheticSkuKeys.clear();
    }

    @Test
    void concurrentDifferentSkusWithOnePriceBalanceSpendOnceAndPreserveReplay() throws Exception {
        String secondSku = "DEV-SHELF-" + UUID.randomUUID();
        seedPublishedSku(secondSku);
        verifyOnePriceConcurrentPurchase(sku, secondSku, false);
    }

    @Test
    void concurrentSameSkuWithOnePriceBalanceNeverDuplicatesInventoryOrSpend() throws Exception {
        verifyOnePriceConcurrentPurchase(sku, sku, true);
    }

    /** AC42の同/異SKUを独立TXで競合させ、409を成功や自動retryへ読み替えない。 */
    private void verifyOnePriceConcurrentPurchase(String firstSku, String secondSku,
                                                  boolean sameSku) throws Exception {
        var owner = owners.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(owner, "balance", 20L);
        owners.saveAndFlush(owner);
        long beforeCommands = commands.countByUserId(me);
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();
        var attempts = concurrentPurchase(firstKey, firstSku, secondKey, secondSku);
        var successful = attempts.stream().filter(attempt -> attempt.result() != null).toList();
        var rejected = attempts.stream().filter(attempt -> attempt.failure() != null).toList();
        assertThat(successful).hasSize(1);
        assertThat(rejected).hasSize(1);
        var winner = successful.get(0);
        var loser = rejected.get(0);
        assertThat(loser.failure().getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007);
        assertThat(loser.failure().getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(winner.result().costPoints()).isEqualTo("20");
        assertThat(winner.result().balanceAfter()).isEqualTo("0");
        assertThat(owners.findByUserId(me).orElseThrow().getBalance()).isZero();
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).singleElement()
                .satisfies(item -> {
                    assertThat(item.getId()).isEqualTo(winner.result().inventoryId());
                    assertThat(item.getSkuKey()).isEqualTo(winner.skuKey());
                    assertThat(item.getAcquisitionKind()).isEqualTo("SHOP");
                    assertThat(item.getPriceVersion()).isEqualTo(1);
                });
        assertThat(commands.countByUserId(me)).isEqualTo(beforeCommands + 1);
        assertThat(commands.findByUserIdAndIdempotencyKey(me, loser.key())).isEmpty();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getCommandId()).isEqualTo(winner.result().commandId());
                    assertThat(row.getEntryKind()).isEqualTo("PURCHASE");
                    assertThat(row.getDeltaPoints()).isEqualTo(-20);
                    assertThat(row.getBalanceAfter()).isZero();
                });

        var originalRequest = new RanchPurchaseRequest(winner.skuKey(), "1", "0");
        assertThat(purchases.purchase(me, winner.key(), originalRequest, NOW.plusSeconds(1)))
                .isEqualTo(winner.result());
        assertThatThrownBy(() -> purchases.purchase(me, loser.key(),
                new RanchPurchaseRequest(loser.skuKey(), "1", "0"), NOW.plusSeconds(2)))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007);
                    assertThat(failure.getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
                });
        // stale拒否後、最新versionで本人が再操作。異SKUは残高不足、同SKUは既所有。
        assertThatThrownBy(() -> purchases.purchase(me, loser.key(),
                new RanchPurchaseRequest(loser.skuKey(), "1", "1"), NOW.plusSeconds(3)))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(sameSku
                            ? RanchErrorCode.RANCH_007 : RanchErrorCode.RANCH_002);
                    assertThat(failure.getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(commands.findByUserIdAndIdempotencyKey(me, loser.key())).isEmpty();
        assertThat(commands.countByUserId(me)).isEqualTo(beforeCommands + 1);
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).hasSize(1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(1);
        assertThat(owners.findByUserId(me).orElseThrow().getBalance()).isZero();
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(purchases.purchase(me, winner.key(), originalRequest, NOW.plusSeconds(4)))
                .isEqualTo(winner.result());
    }

    /** 別threadからSpring proxyを呼び、各purchaseの独立REQUIRES_NEW TXを使う。 */
    private List<PurchaseAttempt> concurrentPurchase(UUID firstKey, String firstSku,
                                                      UUID secondKey, String secondSku) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return attemptPurchase(firstKey, firstSku); });
            var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return attemptPurchase(secondKey, secondSku); });
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("購入競合試験workerが終了していません");
            }
        }
    }

    private PurchaseAttempt attemptPurchase(UUID key, String skuKey) {
        try {
            return new PurchaseAttempt(key, skuKey, purchases.purchase(me, key,
                    new RanchPurchaseRequest(skuKey, "1", "0"), NOW), null);
        } catch (BusinessException failure) {
            return new PurchaseAttempt(key, skuKey, null, failure);
        }
    }

    private record PurchaseAttempt(UUID key, String skuKey, RanchPurchaseResult result,
                                   BusinessException failure) { }

    @Test
    void purchaseAndPlacementAreScopedAndReplayKeepsOldResult() {
        assertThat(shopReader.current(me, NOW).stream().filter(item -> item.skuKey().equals(sku)))
                .singleElement().satisfies(item -> assertThat(item.isOwned()).isFalse());
        UUID purchaseKey = UUID.randomUUID();
        var request = new RanchPurchaseRequest(sku, "1", "0");
        var purchased = purchases.purchase(me, purchaseKey, request, NOW);
        assertThat(purchased.costPoints()).isEqualTo("20");
        assertThat(purchased.balanceAfter()).isEqualTo("80");
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(1)
                .first().satisfies(row -> {
                    assertThat(row.getEntryKind()).isEqualTo("PURCHASE");
                    assertThat(row.getDeltaPoints()).isEqualTo(-20);
                });
        assertThat(shopReader.current(me, NOW).stream().filter(item -> item.skuKey().equals(sku)))
                .singleElement().satisfies(item -> assertThat(item.isOwned()).isTrue());
        UUID placeKey = UUID.randomUUID();
        var placed = slots.place(me, placeKey, "SHELF_1",
                new RanchSlotRequest(purchased.inventoryId(), "0"), NOW.plusSeconds(1));
        assertThat(placed.version()).isEqualTo("1");
        assertThat(inventoryReader.page(me, null, 20).getData()).hasSize(1)
                .first().satisfies(item -> assertThat(item.placedSlotKey()).isEqualTo("SHELF_1"));
        assertThatThrownBy(() -> slots.place(me, UUID.randomUUID(), "SHELF_2",
                new RanchSlotRequest(purchased.inventoryId(), "0"), NOW.plusSeconds(2)))
                .isInstanceOf(BusinessException.class);
        var cleared = slots.clear(me, UUID.randomUUID(), "SHELF_1", "1", NOW.plusSeconds(3));
        assertThat(cleared.inventoryId()).isNull();
        assertThat(cleared.version()).isEqualTo("2");
        assertThat(slots.place(me, placeKey, "SHELF_1",
                new RanchSlotRequest(purchased.inventoryId(), "0"), NOW.plusSeconds(4)))
                .isEqualTo(placed);
        assertThat(purchases.purchase(me, purchaseKey, request, NOW.plusSeconds(5)))
                .isEqualTo(purchased);
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).hasSize(1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(1);
        assertThat(commands.countByUserId(me)).isEqualTo(4);
    }

    @Test
    void otherUsersInventoryAndInvalidVersionCannotChangeThisOwner() {
        Long other = createSyntheticUser();
        enrollment.enroll(other, UUID.randomUUID(), NOW.minusSeconds(10), PROJECTION);
        var otherOwner = owners.findByUserId(other).orElseThrow();
        ReflectionTestUtils.setField(otherOwner, "balance", 100L);
        owners.saveAndFlush(otherOwner);
        var foreign = purchases.purchase(other, UUID.randomUUID(),
                new RanchPurchaseRequest(sku, "1", "0"), NOW);
        assertThat(shopReader.current(me, NOW).stream().filter(item -> item.skuKey().equals(sku)))
                .singleElement().satisfies(item -> assertThat(item.isOwned()).isFalse());
        assertThat(shopReader.current(other, NOW).stream().filter(item -> item.skuKey().equals(sku)))
                .singleElement().satisfies(item -> assertThat(item.isOwned()).isTrue());
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> slots.place(me, UUID.randomUUID(), "SHELF_1",
                new RanchSlotRequest(foreign.inventoryId(), "0"), NOW.plusSeconds(1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> slots.clear(me, UUID.randomUUID(), "SHELF_1",
                "1", NOW.plusSeconds(2))).isInstanceOf(BusinessException.class);
        assertThat(commands.countByUserId(me)).isEqualTo(before);
        assertThat(placements.findByUserIdOrderBySlotKey(me)).allSatisfy(slot -> {
            assertThat(slot.getInventoryId()).isNull();
            assertThat(slot.getVersion()).isZero();
        });
    }

    @Test
    void stalePriceInsufficientBalanceAndDuplicateSkuNeverSpendTwice() {
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> purchases.purchase(me, UUID.randomUUID(),
                new RanchPurchaseRequest(sku.toLowerCase(java.util.Locale.ROOT), "1", "0"), NOW))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getHttpStatusOverride()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> purchases.purchase(me, UUID.randomUUID(),
                new RanchPurchaseRequest(sku, "2", "0"), NOW))
                .isInstanceOf(BusinessException.class);
        var owner = owners.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(owner, "balance", 19L);
        owners.saveAndFlush(owner);
        assertThatThrownBy(() -> purchases.purchase(me, UUID.randomUUID(),
                new RanchPurchaseRequest(sku, "1", "0"), NOW))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.countByUserId(me)).isEqualTo(before);
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).isEmpty();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).isEmpty();
        ReflectionTestUtils.setField(owner, "balance", 100L);
        owners.saveAndFlush(owner);
        purchases.purchase(me, UUID.randomUUID(),
                new RanchPurchaseRequest(sku, "1", "0"), NOW);
        assertThatThrownBy(() -> purchases.purchase(me, UUID.randomUUID(),
                new RanchPurchaseRequest(sku.toLowerCase(java.util.Locale.ROOT), "1", "1"),
                NOW.plusSeconds(1))).isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getHttpStatusOverride()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> purchases.purchase(me, UUID.randomUUID(),
                new RanchPurchaseRequest(sku, "1", "1"), NOW.plusSeconds(1)))
                .isInstanceOf(BusinessException.class);
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).hasSize(1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(1);
        assertThat(commands.countByUserId(me)).isEqualTo(before + 1);
    }

    @Test
    void purchaseVersionOverflowAfterInventoryInsertRollsBackEveryRow() {
        var owner = owners.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(owner, "version", Long.MAX_VALUE);
        owners.saveAndFlush(owner);
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> purchases.purchase(me, UUID.randomUUID(),
                new RanchPurchaseRequest(sku, "1", Long.toString(Long.MAX_VALUE)), NOW))
                .isInstanceOf(ArithmeticException.class);
        assertThat(owners.findByUserId(me).orElseThrow().getBalance()).isEqualTo(100);
        assertThat(owners.findByUserId(me).orElseThrow().getVersion())
                .isEqualTo(Long.MAX_VALUE);
        assertThat(inventory.findByUserIdOrderByAwardedAtDescIdDesc(me)).isEmpty();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).isEmpty();
        assertThat(commands.countByUserId(me)).isEqualTo(before);
    }
}
