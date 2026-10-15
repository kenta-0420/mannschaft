package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.dashboard.MinRole;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * ウィジェット可視性設定の個別項目 DTO。
 * <p>
 * 管理画面向けに、各ウィジェットの最低必要ロール・デフォルトかどうか・最終更新者と更新日時を返す。
 * {@code is_default = true} の場合、{@code updated_by} および {@code updated_at} は {@code null} となる
 * （DB レコードがなくアプリ層デフォルトを返却している状態）。
 * </p>
 */
@Getter
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WidgetVisibilityItemDto {

    /** ウィジェット種別キー（UPPER_SNAKE_CASE）。例: TEAM_NOTICES */
    @JsonProperty("widget_key")
    private final String widgetKey;

    /** 最低必要ロール（PUBLIC / SUPPORTER / MEMBER のいずれか） */
    @JsonProperty("min_role")
    private final MinRole minRole;

    /** true の場合、DB にレコードがなくアプリ層デフォルト値を返している */
    @JsonProperty("is_default")
    private final boolean isDefault;

    /** 最終更新者（is_default = true の場合は null） */
    @JsonProperty("updated_by")
    private final UserSummaryDto updatedBy;

    /** 最終更新日時（is_default = true の場合は null） */
    @JsonProperty("updated_at")
    private final Instant updatedAt;
}
