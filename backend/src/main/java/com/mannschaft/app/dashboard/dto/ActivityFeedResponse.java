package com.mannschaft.app.dashboard.dto;

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
