package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Objects;
import java.util.UUID;

/** 3枠の空行も保持し、配置変更の版を単調増加させる。 */
@Entity
@Table(name = "ranch_room_placements")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchRoomPlacementEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "slot_key", nullable = false, length = 30)
    private String slotKey;
    @Column(name = "inventory_id")
    private UUID inventoryId;
    @Column(name = "version", nullable = false)
    private long version;

    public void place(UUID itemId) {
        inventoryId = Objects.requireNonNull(itemId);
        version = Math.addExact(version, 1);
    }

    public void clear() {
        inventoryId = null;
        version = Math.addExact(version, 1);
    }
}
