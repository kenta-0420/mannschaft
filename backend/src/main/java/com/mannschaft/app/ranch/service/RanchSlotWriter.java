package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchSlotRequest;
import com.mannschaft.app.ranch.dto.RoomSlotSummary;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.entity.RanchRoomPlacementEntity;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** 本人所有置物の3枠配置と解除を成功コマンドと同じ取引で固定する。 */
@Service
@RequiredArgsConstructor
public class RanchSlotWriter {
    private final RanchOwnerRepository owners;
    private final RanchInventoryRepository inventory;
    private final RanchRoomPlacementRepository placements;
    private final RanchCommandRepository commands;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoomSlotSummary place(Long userId, UUID key, String slotKey,
                                 RanchSlotRequest request, Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        validateSlot(slotKey);
        String path = path(slotKey);
        byte[] hash = hasher.hash("SLOT_PUT", path, null, json.valueToTree(request));
        RoomSlotSummary previous = replay(userId, key, "SLOT_PUT", hash);
        if (previous != null) return previous;
        if (request.inventoryId() == null) throw badInput();
        long expected = version(request.version());
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        RanchOwnerEntity owner = owner(userId);
        var item = inventory.findByUserIdAndIdAndRevokedFalse(userId, request.inventoryId())
                .orElseThrow(() -> new BusinessException(RanchErrorCode.RANCH_005, HttpStatus.NOT_FOUND));
        if (!item.getOwnerId().equals(owner.getId())) throw missing();
        RanchRoomPlacementEntity slot = slot(userId, owner, slotKey);
        if (slot.getVersion() != expected) throw conflict();
        if (placements.findByUserIdAndInventoryId(userId, item.getId()).stream()
                .anyMatch(existing -> !existing.getSlotKey().equals(slotKey))) throw conflict();
        slot.place(item.getId());
        placements.saveAndFlush(slot);
        RoomSlotSummary result = summary(slot);
        save(owner, userId, key, "SLOT_PUT", hash, result, now);
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RoomSlotSummary clear(Long userId, UUID key, String slotKey,
                                 String version, Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        validateSlot(slotKey);
        byte[] hash = hasher.hash("SLOT_DELETE", path(slotKey), null,
                json.valueToTree(new VersionBody(version)));
        RoomSlotSummary previous = replay(userId, key, "SLOT_DELETE", hash);
        if (previous != null) return previous;
        long expected = version(version);
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        RanchOwnerEntity owner = owner(userId);
        RanchRoomPlacementEntity slot = slot(userId, owner, slotKey);
        if (slot.getVersion() != expected) throw conflict();
        slot.clear();
        placements.saveAndFlush(slot);
        RoomSlotSummary result = summary(slot);
        save(owner, userId, key, "SLOT_DELETE", hash, result, now);
        return result;
    }

    private RanchRoomPlacementEntity slot(Long userId, RanchOwnerEntity owner, String slotKey) {
        return placements.findByUserIdAndSlotKey(userId, slotKey).filter(row ->
                row.getOwnerId().equals(owner.getId())).orElseThrow(this::missing);
    }

    private RanchOwnerEntity owner(Long userId) {
        return owners.lockByUserId(userId).orElseThrow(() ->
                new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND));
    }

    private RoomSlotSummary summary(RanchRoomPlacementEntity slot) {
        return new RoomSlotSummary(slot.getSlotKey(), slot.getInventoryId(),
                Long.toString(slot.getVersion()));
    }

    private RoomSlotSummary replay(Long userId, UUID key, String type, byte[] hash) {
        var existing = commands.findByUserIdAndIdempotencyKey(userId, key);
        if (existing.isEmpty()) return null;
        RanchCommandEntity command = existing.orElseThrow();
        if (!type.equals(command.getCommandType())
                || !Arrays.equals(hash, command.getBodyHash())) {
            throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
        }
        try {
            return json.readValue(command.getResultJson(), RoomSlotSummary.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }

    private void save(RanchOwnerEntity owner, Long userId, UUID key, String type,
                      byte[] hash, RoomSlotSummary result, Instant now) {
        try {
            commands.saveAndFlush(RanchCommandEntity.builder()
                    .ownerId(owner.getId()).userId(userId).idempotencyKey(key)
                    .commandType(type).bodyHash(hash)
                    .resultJson(json.writeValueAsString(result))
                    .completedAt(now).createdAt(now).build());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_009, exception);
        }
    }

    private void validateSlot(String slotKey) {
        if (!"SHELF_1".equals(slotKey) && !"SHELF_2".equals(slotKey)
                && !"SHELF_3".equals(slotKey)) throw badInput();
    }

    private String path(String slotKey) {
        return "/api/v1/me/ranch/room/slots/" + slotKey;
    }

    private long version(String raw) {
        if (raw == null || !raw.matches("0|[1-9][0-9]*")) throw badInput();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw badInput();
        }
    }

    private BusinessException badInput() {
        return new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
    }

    private BusinessException conflict() {
        return new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT);
    }

    private BusinessException missing() {
        return new BusinessException(RanchErrorCode.RANCH_005, HttpStatus.NOT_FOUND);
    }

    private record VersionBody(String version) { }
}
