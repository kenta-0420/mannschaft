package com.mannschaft.app.chat.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダウンロードURLレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DownloadUrlResponse {

    private final String downloadUrl;
    private final Long expiresInSeconds;
}
