package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * アフィリエイト設定更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateAffiliateConfigRequest {

    @NotBlank
    @Size(max = 20)
    private final String provider;

    @NotBlank
    @Size(max = 100)
    private final String tagId;

    @NotBlank
    @Size(max = 30)
    private final String placement;

    @Size(max = 200)
    private final String description;

    @Size(max = 500)
    private final String bannerImageUrl;

    private final Short bannerWidth;

    private final Short bannerHeight;

    @Size(max = 200)
    private final String altText;

    private final LocalDateTime activeFrom;

    private final LocalDateTime activeUntil;

    @NotNull
    private final Short displayPriority;

    @Size(max = 30)
    private final String targetTemplate;

    @Size(max = 20)
    private final String targetPrefecture;

    @Size(max = 10)
    private final String targetLocale;
}
