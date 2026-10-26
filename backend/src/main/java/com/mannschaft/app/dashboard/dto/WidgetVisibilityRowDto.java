package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.dashboard.MinRole;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * ダッシュボード集約 API レスポンスの widget_visibility[] 配列要素 DTO。
 * <p>
 * 設計書 §4 に従い、各ウィジェットについて
 * {@code widget_key} / {@code min_role} / {@code is_visible} の3項目のみを含む。
 * フロントエンドは {@code is_visible = false} のウィジェットを「データは含まれない」として扱う。
 * </p>
 */
@Getter
@Builder
@Jacksonized
public class WidgetVisibilityRowDto {

    /** ウィジェット種別キー（UPPER_SNAKE_CASE） */
    @JsonProperty("widget_key")
    private final String widgetKey;

    /** 最低必要ロール（PUBLIC / SUPPORTER / MEMBER） */
    @JsonProperty("min_role")
    private final MinRole minRole;

    /** 当該閲覧者ロールに対して可視かどうか */
    @JsonProperty("is_visible")
    private final boolean isVisible;
}
