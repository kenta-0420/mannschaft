package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** 運営の単一制御行。初期の配送pauseは受付保留であり過去の報酬停止ではない。 */
@Entity
@Table(name = "ranch_operational_controls")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchOperationalControlEntity {
    @Id
    private int id;
    @Column(name = "is_care_enabled", nullable = false)
    private boolean careEnabled;
    @Column(name = "is_shop_enabled", nullable = false)
    private boolean shopEnabled;
    @Column(name = "is_delivery_paused", nullable = false)
    private boolean deliveryPaused;
    @Column(name = "version", nullable = false)
    private long version;
    @Column(name = "updated_by", columnDefinition = "bigint unsigned")
    private Long updatedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    /** singleton lock下でのみ適用する。配送停止は報酬停止期間とは別の状態。 */
    public void apply(boolean care, boolean shop, boolean deliveryPause, Long actorId, Instant now) {
        if (id != 1 || actorId == null || now == null) throw new IllegalArgumentException("運営制御の指定が不正です");
        long nextVersion = Math.addExact(version, 1);
        careEnabled = care;
        shopEnabled = shop;
        deliveryPaused = deliveryPause;
        version = nextVersion;
        updatedBy = actorId;
        updatedAt = now;
    }
}
