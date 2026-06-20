package com.mannschaft.app.appearance.dto;

import com.mannschaft.app.appearance.entity.ThemeMode;
import lombok.Builder;
import lombok.Getter;

/**
 * F11.4 外観テーマ設定 — GET/PUT レスポンス DTO。
 *
 * <p>FE {@code useAppearanceStore.ts} は {@code response.data.theme} /
 * {@code response.data.bgColor} / {@code response.data.seasonalThemeId} /
 * {@code response.data.hideChatPreview} を読む。
 * {@link com.mannschaft.app.common.ApiResponse#of} により
 * {@code { "data": { ... } }} 形式で返される。</p>
 */
@Getter
@Builder
public class AppearanceResponse {

    /** テーマモード（LIGHT / DARK）。 */
    private final ThemeMode theme;

    /** 背景色 HEX コード（例: {@code #f3efe0}）。 */
    private final String bgColor;

    /** 季節テーマ ID（null 許容）。 */
    private final Long seasonalThemeId;

    /** チャットプレビュー非表示フラグ。 */
    private final boolean hideChatPreview;
}
