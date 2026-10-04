package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchPurchaseRequest;
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
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    private Long me;
    private String sku;

    @BeforeEach
    void seedOwnerAndPublishedSku() {
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        sku = "DEV-SHELF-" + UUID.randomUUID();
        enrollment.enroll(me, UUID.randomUUID(), NOW.minusSeconds(10), PROJECTION);
        var owner = owners.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(owner, "balance", 100L);
        owners.saveAndFlush(owner);
        collectibles.saveAndFlush(RanchCollectibleCatalogEntity.builder()
                .collectibleKey(sku).labelKey("ranch.dev.shelf1")
                .assetKey("dev-shelf-1").sourceKind("SHOP").active(true)
                .createdAt(NOW.minusSeconds(1)).updatedAt(NOW.minusSeconds(1)).build());
        catalog.saveAndFlush(RanchShopCatalogEntity.builder()
                .skuKey(sku).collectibleKey(sku)
                .pricePoints(20).priceVersion(1).active(true)
                .createdAt(NOW.minusSeconds(1)).build());
    }

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
        Long other = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrollment.enroll(other, UUID.randomUUID(), NOW.minusSeconds(10), PROJECTION);
        var otherOwner = owners.findByUserId(other).orElseThrow();
        ReflectionTestUtils.setField(otherOwner, "balance", 100L);
        owners.saveAndFlush(otherOwner);
        var foreign = purchases.purchase(other, UUID.randomUUID(),
                new RanchPurchaseRequest(sku, "1", "0"), NOW);
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
