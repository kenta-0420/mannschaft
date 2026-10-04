package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchInventoryItem;
import com.mannschaft.app.ranch.entity.RanchInventoryEntity;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** 本人の永久置物だけを署名済みkeyset cursorで取得する。 */
@Service
@RequiredArgsConstructor
public class RanchInventoryQueryReader {
    private final RanchInventoryRepository inventory;
    private final RanchCollectibleCatalogRepository collectibles;
    private final RanchRoomPlacementRepository placements;
    private final RanchRecordCursorCodec cursors;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public CursorPagedResponse<RanchInventoryItem> page(Long userId, String cursor, int limit) {
        Objects.requireNonNull(userId);
        if (limit < 1 || limit > 100) {
            throw new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
        }
        RanchRecordCursorCodec.Position start = cursor == null
                ? null : cursors.decodeInventory(userId, cursor);
        List<RanchInventoryEntity> rows = inventory.pageForUser(userId,
                start == null ? null : start.occurredAt(),
                start == null ? null : start.id(), PageRequest.of(0, limit + 1));
        boolean hasNext = rows.size() > limit;
        List<RanchInventoryEntity> page = hasNext ? rows.subList(0, limit) : rows;
        Map<UUID, String> placed = placements.findByUserIdOrderBySlotKey(userId).stream()
                .filter(slot -> slot.getInventoryId() != null)
                .collect(Collectors.toMap(slot -> slot.getInventoryId(),
                        slot -> slot.getSlotKey(), (first, second) -> first));
        var keys = page.stream().map(RanchInventoryEntity::getCollectibleKey).distinct().toList();
        var catalog = collectibles.findAllById(keys).stream()
                .collect(Collectors.toMap(approved -> approved.getCollectibleKey(),
                        approved -> approved));
        List<RanchInventoryItem> data = page.stream().map(item -> {
            var approved = catalog.get(item.getCollectibleKey());
            if (approved == null) {
                throw new BusinessException(RanchErrorCode.RANCH_008,
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return new RanchInventoryItem(item.getId(), item.getCollectibleKey(),
                    approved.getLabelKey(), approved.getAssetKey(), item.getAcquisitionKind(),
                    item.getAwardedAt(), item.isRevoked(), placed.get(item.getId()));
        }).toList();
        String next = null;
        if (hasNext) {
            RanchInventoryEntity last = page.get(page.size() - 1);
            next = cursors.encodeInventory(userId, last.getAwardedAt(), last.getId());
        }
        return CursorPagedResponse.of(data,
                new CursorPagedResponse.CursorMeta(next, hasNext, limit));
    }
}
