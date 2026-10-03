package com.mannschaft.app.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * カレンダーレイヤー1件（F03.19 §4.3）。
 *
 * <p>所属スコープ ＋ 解決済み色 ＋ 表示可否の合成ビュー。{@code GET /me/calendar-layers} の
 * 要素型であり、{@code PATCH /me/calendar-layers/{scopeType}/{scopeId}} の応答型でもある。</p>
 */
@Schema(description = "カレンダーレイヤー（所属スコープ＋解決済み色＋表示可否）")
public record CalendarLayerResponse(

        @Schema(description = "レイヤー種別", example = "TEAM",
                allowableValues = {"PERSONAL", "TEAM", "ORGANIZATION"})
        String scopeType,

        @Schema(description = "レイヤー対象ID（PERSONAL は常に 0）", example = "42")
        Long scopeId,

        @Schema(description = "表示名（PERSONAL は i18n キーを FE が翻訳するためのプレースホルダ）",
                example = "青葉FC")
        String scopeName,

        @Schema(description = "PERSONAL のみ非 null の i18n キー。TEAM/ORGANIZATION は null",
                example = "schedule.calendar.layer.personal", nullable = true)
        String scopeNameKey,

        @Schema(description = "アイコン表示URL（未設定は null）", nullable = true)
        String scopeIconUrl,

        @Schema(description = "解決済み表示色（#RRGGBB 大文字）", example = "#059669")
        String color,

        @Schema(description = "色の由来。本 API は LAYER_USER / LAYER_AUTO のみ返す")
        CalendarColorSource colorSource,

        @Schema(description = "既定で非表示にするか", example = "false")
        boolean hidden) {
}
