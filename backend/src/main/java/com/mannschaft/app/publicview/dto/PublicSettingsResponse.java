package com.mannschaft.app.publicview.dto;

/** 公開閲覧権限とは独立して、管理認可後に返す現在の公開設定。 */
public record PublicSettingsResponse(
        Long numericId,
        boolean timelinePostsPublic,
        boolean publicEventsEnabled) {
}
