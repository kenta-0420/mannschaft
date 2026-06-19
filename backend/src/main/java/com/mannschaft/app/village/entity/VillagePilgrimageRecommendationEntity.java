package com.mannschaft.app.village.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 巡礼推薦エンティティ（F17.1 Phase 3-β）。
 *
 * <p>日次バッチが {@code (user_id, recommended_date)} の単位で 1 行作成し、
 * ユーザーが推薦村を訪問すると {@link #visitedAt} を記録する。</p>
 *
 * <p>原則1: {@code user_id} は FK を張らない。</p>
 * <p>原則6: PK は UUIDv7。</p>
 */
@Entity
@Table(name = "village_pilgrimage_recommendations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class VillagePilgrimageRecommendationEntity extends UuidV7Entity {

    /** 推薦先ユーザーID（FK 張らない・原則1） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** FK → villages.id（同一ドメイン CASCADE） */
    @Column(name = "recommended_village_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID recommendedVillageId;

    /** 推薦日（日次バッチが生成） */
    @Column(name = "recommended_date", nullable = false)
    private LocalDate recommendedDate;

    /** 推薦理由（カテゴリ一致など） */
    @Column(name = "reason", length = 100)
    private String reason;

    /** 訪問時に記録 */
    @Column(name = "visited_at")
    private LocalDateTime visitedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
