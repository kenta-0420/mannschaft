package com.mannschaft.app.dashboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ウィジェット設定レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class WidgetSettingResponse {

    private final String widgetKey;
    private final String name;
    private final boolean isVisible;
    private final int sortOrder;
    private final boolean isModuleEnabled;
    private final String disabledReason;
}
