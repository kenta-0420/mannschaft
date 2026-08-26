package com.mannschaft.app.dashboard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ウィジェット設定一括更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateWidgetSettingsRequest {

    @NotBlank
    private final String scopeType;

    /**
     * スコープID。slug 文字列（例: {@code org-000001} / {@code team-000017}）または数値文字列を受け付ける。
     * PERSONAL の場合は未送信（null）。slug 移行に伴い Long から String へ変更（数値も後方互換で解決可能）。
     */
    private final String scopeId;

    @NotNull
    @Size(min = 1)
    @Valid
    private final List<WidgetSettingItem> widgets;

    /**
     * 個別ウィジェット設定。
     */
    @Getter
    @RequiredArgsConstructor
    public static class WidgetSettingItem {

        @NotBlank
        private final String widgetKey;

        @NotNull
        private final Boolean isVisible;

        @NotNull
        private final Integer sortOrder;
    }
}
