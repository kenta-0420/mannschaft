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

/** ranch_affinity_unitsの本人スコープ永続骨格。 */
@Entity
@Table(name = "ranch_affinity_units")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchAffinityUnitEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "dinosaur_id", nullable = false)
    private UUID dinosaurId;
    @Column(name = "earned_on", nullable = false)
    private LocalDate earnedOn;
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;
    @Column(name = "gain", nullable = false)
    private long gain;
}
