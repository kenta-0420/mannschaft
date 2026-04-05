package com.mannschaft.app.dashboard;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * アクティビティフィード書き込み用のアプリケーションイベント。
 * 各機能の Service 層がコンテンツ操作完了後に発行し、
 * ActivityFeedEventListener が非同期で activity_feed テーブルに INSERT する。
 */
@Getter
@RequiredArgsConstructor
public class ActivityEvent {

    private final ActivityType activityType;
    private final ScopeType scopeType;
    private final Long scopeId;
    private final Long actorId;
    private final TargetType targetType;
    private final Long targetId;
}
