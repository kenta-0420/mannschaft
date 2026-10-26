package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * ダイジェスト自動生成設定レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class DigestConfigResponse {

    private final Long id;
    private final String scheduleType;
    private final LocalTime scheduleTime;
    private final Integer scheduleDayOfWeek;
    private final LocalDateTime lastExecutedAt;
    private final String timezone;
    private final String digestStyle;
    private final Boolean includeReactions;
    private final Boolean includePolls;
    private final Boolean includeDiffFromPrevious;
    private final Boolean autoPublish;
    private final String stylePresets;
    private final Integer minPostsThreshold;
    private final Integer maxPostsPerDigest;
    private final Integer contentMaxChars;
    private final String language;
    private final String customPromptSuffix;
    private final String autoTagIds;
    private final Boolean isEnabled;
}
