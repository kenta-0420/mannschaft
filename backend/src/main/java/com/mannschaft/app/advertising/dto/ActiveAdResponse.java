package com.mannschaft.app.advertising.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 現在有効な広告レスポンス（公開API用）。
 */
@Getter
@RequiredArgsConstructor
public class ActiveAdResponse {

    private final Long id;
    private final String provider;
    private final String tagId;
    private final String placement;
    private final String bannerImageUrl;
    private final Short bannerWidth;
    private final Short bannerHeight;
    private final String altText;
    private final Short displayPriority;
}
