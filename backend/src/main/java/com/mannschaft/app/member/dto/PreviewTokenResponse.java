package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * プレビュートークン発行レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PreviewTokenResponse {

    private final Long id;
    private final String previewToken;
    private final String previewUrl;
    private final LocalDateTime expiresAt;
}
