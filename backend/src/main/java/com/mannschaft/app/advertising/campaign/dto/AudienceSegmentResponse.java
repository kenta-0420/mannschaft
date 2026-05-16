package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * F09.17 ターゲティングセグメントレスポンス。
 * {@code segmentValue} は格納時の JSON 文字列を {@code Map} にデシリアライズして返す。
 */
public record AudienceSegmentResponse(
        UUID id,
        UUID campaignId,
        AdSegmentType segmentType,
        Map<String, Object> segmentValue,
        AdSegmentInclusionMode inclusionMode,
        LocalDateTime createdAt
) {
}
