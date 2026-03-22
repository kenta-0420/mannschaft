package com.mannschaft.app.promotion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * プロモーションセグメントエンティティ。
 */
@Entity
@Table(name = "promotion_segments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PromotionSegmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long promotionId;

    @Column(nullable = false, length = 30)
    private String segmentType;

    @Column(nullable = false, columnDefinition = "JSON")
    private String segmentValue;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
