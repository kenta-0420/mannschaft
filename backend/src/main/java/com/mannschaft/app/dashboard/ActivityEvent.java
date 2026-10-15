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

    /**
     * F03.18: 変更差分（JSON文字列）。nullable。発行元がJSON化して積む。
     */
    private final String detail;
}
