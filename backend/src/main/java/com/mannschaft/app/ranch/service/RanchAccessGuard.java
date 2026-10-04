package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** URL/body resourceと認証本人IDを同一条件で照合する私有resource guard。 */
@Service
@RequiredArgsConstructor
public class RanchAccessGuard {
    private final RanchCommandRepository commands;
    private final RanchRoomPlacementRepository slots;
    private final RanchInventoryRepository inventory;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void requireOwnedCommand(Long userId, UUID commandId) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(commandId);
        commands.findByUserIdAndId(userId, commandId).orElseThrow(() ->
                new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void requireOwnedSlot(Long userId, String slotKey) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(slotKey);
        slots.findByUserIdAndSlotKey(userId, slotKey).orElseThrow(this::missing);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void requireOwnedPlacement(Long userId, String slotKey, UUID inventoryId) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(slotKey);
        Objects.requireNonNull(inventoryId);
        var slot = slots.findByUserIdAndSlotKey(userId, slotKey).orElseThrow(this::missing);
        var item = inventory.findByUserIdAndIdAndRevokedFalse(userId, inventoryId)
                .orElseThrow(this::missing);
        if (!slot.getOwnerId().equals(item.getOwnerId())) throw missing();
    }

    private BusinessException missing() {
        return new BusinessException(RanchErrorCode.RANCH_005, HttpStatus.NOT_FOUND);
    }
}
