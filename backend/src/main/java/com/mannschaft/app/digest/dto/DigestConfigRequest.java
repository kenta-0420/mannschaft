package com.mannschaft.app.digest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;
import java.util.List;

/**
 * ダイジェスト自動生成設定の作成・更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class DigestConfigRequest {

    @NotBlank
    private final String scopeType;

    @NotNull
    private final Long scopeId;

    @NotBlank
    private final String scheduleType;

    private final LocalTime scheduleTime;

    @Min(0)
    @Max(6)
    private final Integer scheduleDayOfWeek;

    @NotBlank
    private final String digestStyle;

    private final Boolean autoPublish;

    private final String stylePresets;

    private final Boolean includeReactions;

    private final Boolean includePolls;

    private final Boolean includeDiffFromPrevious;

    @Positive
    private final Integer minPostsThreshold;

    @Positive
    private final Integer maxPostsPerDigest;

    @NotBlank
    private final String timezone;

    @Positive
    @Max(5000)
    private final Integer contentMaxChars;

    private final String language;

    @Size(max = 500)
    private final String customPromptSuffix;

    private final List<Long> autoTagIds;
}
