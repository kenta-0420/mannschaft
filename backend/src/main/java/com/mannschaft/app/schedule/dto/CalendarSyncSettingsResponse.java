package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * カレンダー同期設定一覧レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CalendarSyncSettingsResponse {

    private final boolean isConnected;
    private final String googleAccountEmail;
    private final boolean personalSyncEnabled;
    private final List<SyncSettingItem> syncSettings;

    /**
     * スコープ別同期設定アイテム。
     */
    public record SyncSettingItem(
            String scopeType,
            Long scopeId,
            String scopeName,
            boolean isEnabled
    ) {}
}
