package com.mannschaft.app.chat.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Pre-signed URLレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class UploadUrlResponse {

    private final String uploadUrl;
    private final String fileKey;
    private final Long expiresInSeconds;
}
