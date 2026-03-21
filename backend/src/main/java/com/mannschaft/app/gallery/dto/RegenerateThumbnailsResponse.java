package com.mannschaft.app.gallery.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * サムネイル再生成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RegenerateThumbnailsResponse {

    private final String jobId;
    private final String status;
}
