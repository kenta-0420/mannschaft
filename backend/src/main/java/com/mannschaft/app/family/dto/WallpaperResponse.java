package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 壁紙レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class WallpaperResponse {

    private final Long id;
    private final String templateSlug;
    private final String name;
    private final String imageUrl;
    private final String thumbnailUrl;
    private final String category;
    private final int sortOrder;
    private final boolean isActive;
}
