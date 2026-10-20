package com.mannschaft.app.ranch.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 牧場の新規表はUUIDv7とUTC瞬間を共通に持つ。 */
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class RanchEntity extends UuidV7Entity {
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void stampCreation() {
        createdAt = (createdAt == null ? Instant.now() : createdAt)
                .truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }
    @PreUpdate
    protected void stampUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
