package com.mannschaft.app.promotion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * プロモーション配信サマリーエンティティ。
 */
@Entity
@Table(name = "promotion_delivery_summaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class PromotionDeliverySummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long promotionId;

    @Column(nullable = false)
    private LocalDate summaryDate;

    @Column(nullable = false)
    @Builder.Default
    private Integer deliveredCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer openedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer failedCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
