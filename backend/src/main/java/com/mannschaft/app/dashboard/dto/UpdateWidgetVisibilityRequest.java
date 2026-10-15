package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.dashboard.MinRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ウィジェット可視性設定の一括更新リクエスト。
 * <p>
 * 設計書 §4 に従い、リクエストに含まれない widget_key の既存レコードは変更しない（差分更新）。
 * リクエストの min_role がアプリ層デフォルトと一致する場合、Service 層で DB レコードを DELETE する。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateWidgetVisibilityRequest {

    /** 更新対象ウィジェットリスト（最大 50 件） */
    @NotNull
    @Size(max = 50)
    @Valid
    private final List<WidgetVisibilityUpdateItem> widgets;

    /**
     * 個別ウィジェットの更新項目。
     */
    @Getter
    @RequiredArgsConstructor
    public static class WidgetVisibilityUpdateItem {

        /**
         * ウィジェット種別キー。UPPER_SNAKE_CASE のみ許容。
         * 数字やハイフン・ドットは許容しない。
         */
        @JsonProperty("widget_key")
        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                message = "widget_key は UPPER_SNAKE_CASE で指定してください")
        private final String widgetKey;

        /** 最低必要ロール（PUBLIC / SUPPORTER / MEMBER のいずれか） */
        @JsonProperty("min_role")
        @NotNull
        private final MinRole minRole;
    }
}
