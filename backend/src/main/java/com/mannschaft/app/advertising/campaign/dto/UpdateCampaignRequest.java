package com.mannschaft.app.advertising.campaign.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * F09.17 メッセージ型キャンペーン更新リクエスト。
 *
 * <p>DRAFT 状態のキャンペーンのみ更新可能。
 * status / moderationStatus / consumedBudgetYen は本リクエストでは変更しない。</p>
 */
public record UpdateCampaignRequest(
        @NotBlank
        @Size(min = 1, max = 120)
        String name,

        @NotNull
        @Min(1_000L)
        @Max(100_000_000L)
        Long totalBudgetYen,

        @NotNull
        LocalDateTime startsAt,

        @NotNull
        LocalDateTime endsAt,

        @NotBlank
        @Size(max = 50)
        String scheduledTimezone,

        @Min(1)
        @Max(30)
        Integer frequencyCapOverride
) {
}
