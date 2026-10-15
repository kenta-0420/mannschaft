package com.mannschaft.app.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * アクティビティフィードレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ActivityFeedResponse {

    private final Long id;
    private final String type;
    private final ActorSummary actor;
    private final String scopeType;
    private final Long scopeId;
    private final String scopeName;
    private final String targetType;
    private final Long targetId;
    private final String summary;

    /**
     * F03.18: 変更差分（構造化データ）。SCHEDULE系活動のみ非null、既存種別は常にnull。
     * 発行元（ScheduleService）が未結線のため現時点では常にnull。
     */
    @Schema(type = "object", nullable = true, description = "変更差分（SCHEDULE系のみ非null）")
    private final Object detail;

    private final LocalDateTime createdAt;

    /**
     * アクター（行動者）のサマリー情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ActorSummary {
        private final Long id;
        private final String displayName;
        private final String avatarUrl;
    }
}
