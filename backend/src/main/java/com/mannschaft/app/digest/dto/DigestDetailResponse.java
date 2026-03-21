package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイジェスト詳細レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class DigestDetailResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final LocalDateTime periodStart;
    private final LocalDateTime periodEnd;
    private final Integer postCount;
    private final String digestStyle;
    private final String generatedTitle;
    private final String generatedBody;
    private final String generatedExcerpt;
    private final String aiModel;
    private final Integer aiInputTokens;
    private final Integer aiOutputTokens;
    private final String status;
    private final Long blogPostId;
    private final DigestTriggeredByResponse triggeredBy;
    private final LocalDateTime createdAt;
}
