package com.mannschaft.app.ranch.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RanchEntityTimestampTest {
    @Test
    void databaseTimestampsUseMicrosecondPrecision() {
        RanchOwnerEntity owner = RanchOwnerEntity.builder()
                .createdAt(Instant.parse("2026-10-04T02:00:00.123456789Z"))
                .build();

        owner.stampCreation();
        assertThat(owner.getCreatedAt()).isEqualTo(Instant.parse("2026-10-04T02:00:00.123456Z"));
        assertThat(owner.getUpdatedAt()).isEqualTo(owner.getCreatedAt());

        owner.stampUpdate();
        assertThat(owner.getUpdatedAt().getNano() % 1_000).isZero();
    }
}
