package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイレクトメール画像アップロードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DirectMailImageUploadResponse {

    private final Long id;
    private final String s3Key;
    private final String fileName;
    private final Integer fileSize;
    private final String contentType;
    private final String imageUrl;
    private final LocalDateTime createdAt;
}
