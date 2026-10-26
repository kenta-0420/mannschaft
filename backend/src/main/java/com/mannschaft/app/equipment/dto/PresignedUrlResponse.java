package com.mannschaft.app.equipment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Pre-signed URL レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresignedUrlResponse {

    private final String uploadUrl;
    private final String s3Key;
    private final Integer expiresIn;
}
