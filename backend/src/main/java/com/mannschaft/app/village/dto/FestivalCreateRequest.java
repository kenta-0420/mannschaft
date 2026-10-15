package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * F17.1 Phase 2 U5 — 村お祭り作成リクエスト。
 *
 * <p>{@code description} / {@code bannerR2Key} / {@code themeColorHex} は省略可。
 * 期間整合性（{@code startsAt < endsAt}）と色フォーマット（{@code #RRGGBB}）の検証は
 * Service 側で行い、{@link com.mannschaft.app.village.VillageErrorCode#FESTIVAL_INVALID_PERIOD}
 * / {@link com.mannschaft.app.village.VillageErrorCode#FESTIVAL_INVALID_COLOR} を投げる。</p>
 */
public record FestivalCreateRequest(
        @NotBlank @Size(max = 100) String title,
        @Size(max = 5000) String description,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt,
        @Size(max = 255) String bannerR2Key,
        @Size(max = 7) String themeColorHex) {
}
