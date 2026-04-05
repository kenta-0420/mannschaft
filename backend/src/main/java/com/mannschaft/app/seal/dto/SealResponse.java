package com.mannschaft.app.seal.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 電子印鑑レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SealResponse {

    private final Long id;
    private final Long userId;
    private final String variant;
    private final String displayText;
    private final String svgData;
    private final String sealHash;
    private final Integer generationVersion;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
