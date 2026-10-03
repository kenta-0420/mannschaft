package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
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
 * F09.17 キャンペーンターゲティング条件 (INCLUDE/EXCLUDE)。
 * {@code segment_value} は F09.2 SegmentEvaluator スキーマに準拠した JSON 文字列。
 */
@Entity
@Table(name = "ad_audience_segments")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class AdAudienceSegment extends UuidV7Entity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment_type", nullable = false, length = 30)
    private AdSegmentType segmentType;

    @Column(name = "segment_value", columnDefinition = "JSON", nullable = false)
    private String segmentValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "inclusion_mode", nullable = false, length = 10)
    private AdSegmentInclusionMode inclusionMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.inclusionMode == null) {
            this.inclusionMode = AdSegmentInclusionMode.INCLUDE;
        }
    }
}
