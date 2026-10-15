package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * カルテ写真レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChartPhotoResponse {

    private final Long id;
    private final String photoType;
    private final String signedUrl;
    private final LocalDateTime signedUrlExpiresAt;
    private final String originalFilename;
    private final Integer fileSizeBytes;
    private final String note;
    private final Boolean isSharedToCustomer;
}
