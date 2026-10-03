package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** ranch_room_placementsの本人スコープ永続骨格。 */
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
    @Column(name = "inventory_id", nullable = true)
    private UUID inventoryId;
    @Column(name = "version", nullable = false)
    private long version;
}
