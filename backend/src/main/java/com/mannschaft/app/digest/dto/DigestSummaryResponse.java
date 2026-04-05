package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイジェスト一覧用レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class DigestSummaryResponse {

    private final Long id;
    private final LocalDateTime periodStart;
    private final LocalDateTime periodEnd;
    private final Integer postCount;
    private final String digestStyle;
    private final String generatedTitle;
    private final String status;
    private final Long blogPostId;
    private final LocalDateTime createdAt;
}
