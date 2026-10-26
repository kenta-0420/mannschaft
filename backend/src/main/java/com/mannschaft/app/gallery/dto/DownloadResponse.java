package com.mannschaft.app.gallery.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダウンロードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DownloadResponse {

    private final String downloadUrl;
    private final String originalFilename;
    private final Integer photoCount;
    private final LocalDateTime expiresAt;
}
