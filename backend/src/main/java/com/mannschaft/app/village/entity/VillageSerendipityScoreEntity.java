package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — ご縁スコア Entity。
 *
 * <p>村人同士の出会い頻度・交流度を集計するレコード。日次バッチが前日分の
 * 井戸端会議返信ペア等から {@code encounterCount} / {@code interactionScore} を
 * 加算的に更新する。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>原則1: {@code userId} には FK を張らず、整合性はアプリ層で保証する。</li>
 *   <li>原則6: 主キーは UUIDv7（{@link UuidV7Entity} 継承）。</li>
 *   <li>原則7 対象外（村ドメインは組織テナントスコープ外）。</li>
 * </ul>
 */
@Entity
@Table(name = "village_serendipity_scores")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillageSerendipityScoreEntity extends UuidV7Entity {

    /** FK → villages.id（同一ドメイン CASCADE） */
    @Column(name = "village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID villageId;

    /** ユーザー ID（FK 張らない・原則1） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 出会い回数（累積、>= 0） */
    @Column(name = "encounter_count", nullable = false)
    private Long encounterCount;

    /** 交流スコア（累積、>= 0） */
    @Column(name = "interaction_score", nullable = false)
    private Long interactionScore;

    /** 最終更新日時（バッチ実行時刻） */
    @Column(name = "last_updated_at", nullable = false)
    private LocalDateTime lastUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.lastUpdatedAt == null) {
            this.lastUpdatedAt = now;
        }
        if (this.encounterCount == null) {
            this.encounterCount = 0L;
        }
        if (this.interactionScore == null) {
            this.interactionScore = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
