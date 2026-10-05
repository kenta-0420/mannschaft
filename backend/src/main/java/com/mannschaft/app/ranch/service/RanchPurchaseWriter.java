package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchPurchaseRequest;
import com.mannschaft.app.ranch.dto.RanchPurchaseResult;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchInventoryEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchShopCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 公開済みSKU価格版、残高、永久置物、PURCHASE台帳を同じ牧場取引で確定する。 */
@Service
@RequiredArgsConstructor
public class RanchPurchaseWriter {
    private static final String TYPE = "PURCHASE";
    private static final String RESOURCE = "/api/v1/me/ranch/purchases";

    private final RanchOwnerRepository owners;
    private final RanchInventoryRepository inventory;
    private final RanchShopCatalogRepository catalog;
    private final RanchCollectibleCatalogRepository collectibles;
    private final RanchPointLedgerRepository ledger;
    private final RanchCommandRepository commands;
    private final RanchShopControl shop;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RanchPurchaseResult purchase(Long userId, UUID key,
                                        RanchPurchaseRequest request, Instant serverTime) {
        return purchaseOutcome(userId, key, request, serverTime).result();
    }

    /** 保存済み成功と初回を同じTX内で判別する。公開DTOにはtransport状態を混ぜない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PurchaseOutcome purchaseOutcome(Long userId, UUID key,
                                           RanchPurchaseRequest request, Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        byte[] hash = hasher.hash(TYPE, RESOURCE, null, json.valueToTree(request));
        var previous = commands.findByUserIdAndIdempotencyKey(userId, key);
        if (previous.isPresent()) {
            RanchCommandEntity command = previous.orElseThrow();
            if (!TYPE.equals(command.getCommandType())
                    || !Arrays.equals(hash, command.getBodyHash())) {
                throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
            }
            return new PurchaseOutcome(decode(command.getResultJson()), false);
        }
        if (request.skuKey() == null || request.skuKey().isBlank()
                || request.priceVersion() == null || request.priceVersion().isBlank()) {
            throw new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
        }
        try {
            RanchAcquisitionKey.shopSku(request.skuKey());
        } catch (IllegalArgumentException exception) {
            throw badInput();
        }
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        if (!shop.enabled()) throw unavailable();
        long requestedPriceVersion = positiveVersion(request.priceVersion());
        var current = catalog.findFirstBySkuKeyAndActiveTrueOrderByPriceVersionDesc(
                        request.skuKey())
                .orElseThrow(this::unavailable);
        if (!current.getSkuKey().equals(request.skuKey())) throw badInput();
        if (current.getPriceVersion() != requestedPriceVersion) throw conflict();
        collectibles.findByCollectibleKeyAndActiveTrue(current.getCollectibleKey())
                .filter(row -> "SHOP".equals(row.getSourceKind()))
                .orElseThrow(this::unavailable);
        RanchOwnerEntity owner = owners.lockByUserId(userId).orElseThrow(() ->
                new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND));
        if (owner.getVersion() != version(request.version())) throw conflict();
        byte[] acquisitionKey = RanchAcquisitionKey.shopSku(current.getSkuKey());
        if (inventory.findByUserIdAndAcquisitionKindAndAcquisitionKey(
                userId, "SHOP", acquisitionKey).isPresent()) {
            throw conflict();
        }
        long price = current.getPricePoints();
        if (price <= 0) throw unavailable();
        if (owner.getBalance() < price) {
            throw new BusinessException(RanchErrorCode.RANCH_002, HttpStatus.CONFLICT);
        }

        RanchInventoryEntity item = inventory.saveAndFlush(RanchInventoryEntity.builder()
                .ownerId(owner.getId()).userId(userId).skuKey(current.getSkuKey())
                .collectibleKey(current.getCollectibleKey())
                .acquisitionKind("SHOP").acquisitionKey(acquisitionKey)
                .priceVersion(current.getPriceVersion())
                .awardedAt(now).revoked(false).createdAt(now).build());
        owner.spend(price);
        owners.save(owner);
        UUID commandId = UuidV7.generate();
        ledger.save(RanchPointLedgerEntity.builder()
                .ownerId(owner.getId()).userId(userId).commandId(commandId)
                .entryKind("PURCHASE").deltaPoints(-price)
                .balanceAfter(owner.getBalance()).deltaXp(0)
                .ruleSnapshot(encode(Map.of(
                        "skuKey", current.getSkuKey(),
                        "priceVersion", Long.toString(current.getPriceVersion()),
                        "pricePoints", price,
                        "catalogId", current.getId().toString())))
                .occurredAt(now).createdAt(now).build());
        RanchPurchaseResult result = new RanchPurchaseResult(commandId, item.getId(),
                current.getSkuKey(), Long.toString(price),
                Long.toString(current.getPriceVersion()),
                Long.toString(owner.getBalance()), now);
        RanchCommandEntity command = RanchCommandEntity.builder()
                .ownerId(owner.getId()).userId(userId).idempotencyKey(key)
                .commandType(TYPE).bodyHash(hash).resultJson(encode(result))
                .completedAt(now).createdAt(now).build();
        command.setId(commandId);
        commands.saveAndFlush(command);
        return new PurchaseOutcome(result, true);
    }

    public record PurchaseOutcome(RanchPurchaseResult result, boolean createdNow) { }

    private RanchPurchaseResult decode(String saved) {
        try {
            return json.readValue(saved, RanchPurchaseResult.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_009, exception);
        }
    }

    private long version(String raw) {
        if (raw == null || !raw.matches("0|[1-9][0-9]*")) {
            throw new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
        }
    }

    private long positiveVersion(String raw) {
        long parsed = version(raw);
        if (parsed <= 0) throw badInput();
        return parsed;
    }

    private BusinessException badInput() {
        return new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
    }

    private BusinessException conflict() {
        return new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT);
    }

    private BusinessException unavailable() {
        return new BusinessException(RanchErrorCode.RANCH_004, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
