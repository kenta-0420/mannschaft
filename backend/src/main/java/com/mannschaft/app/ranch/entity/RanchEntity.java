package com.mannschaft.app.ranch.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;

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
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = createdAt;
    }
    @PreUpdate
    protected void stampUpdate() {
        updatedAt = Instant.now();
    }
}
