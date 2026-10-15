package com.mannschaft.app.gallery.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * サムネイル再生成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RegenerateThumbnailsRequest {

    private final Long teamId;

    private final Long organizationId;
}
