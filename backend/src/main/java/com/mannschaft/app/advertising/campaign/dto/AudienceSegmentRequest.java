package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * F09.17 ターゲティングセグメント単体リクエスト。
 *
 * <p>{@code segmentValue} は F09.2 SegmentEvaluator スキーマに準拠した JSON。
 * 受信時は Map で受け、Service 層で {@code String} (JSON) にシリアライズして格納する。</p>
 */
public record AudienceSegmentRequest(
        @NotNull
        AdSegmentType segmentType,

        @NotNull
        Map<String, Object> segmentValue,

        @NotNull
        AdSegmentInclusionMode inclusionMode
) {
}
