package com.mannschaft.app.advertising.campaign.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * F09.17 メッセージ型キャンペーン作成リクエスト。
 *
 * <p>設計書 §4 {@code POST /api/v1/advertiser/campaigns/messaging} のバリデーション仕様に準拠。
 * 作成時の {@code status} は常に {@code DRAFT}、{@code moderationStatus} は {@code PENDING} を
 * サービス側で強制セットする。</p>
 */
public record CreateCampaignRequest(
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

        /** NULL 時はデフォルト週 3 件。1〜30 を許容。 */
        @Min(1)
        @Max(30)
        Integer frequencyCapOverride
) {
}
