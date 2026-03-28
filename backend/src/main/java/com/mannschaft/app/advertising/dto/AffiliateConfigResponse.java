package com.mannschaft.app.advertising.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * アフィリエイト設定レスポンス（SYSTEM_ADMIN用）。
 */
@Getter
@RequiredArgsConstructor
public class AffiliateConfigResponse {

    private final Long id;
    private final String provider;
    private final String tagId;
    private final String placement;
    private final String description;
    private final String bannerImageUrl;
    private final Short bannerWidth;
    private final Short bannerHeight;
    private final String altText;
    private final Boolean isActive;
    private final LocalDateTime activeFrom;
    private final LocalDateTime activeUntil;
    private final Short displayPriority;
    private final String targetTemplate;
    private final String targetPrefecture;
    private final String targetLocale;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
