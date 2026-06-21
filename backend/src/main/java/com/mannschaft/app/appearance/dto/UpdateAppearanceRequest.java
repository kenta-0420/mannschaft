package com.mannschaft.app.appearance.dto;

import com.mannschaft.app.appearance.entity.ThemeMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F11.4 外観テーマ設定 — PUT リクエスト DTO。
 *
 * <p>バリデーション:</p>
 * <ul>
 *   <li>{@code theme}: 必須・null 不可（不正値は JSON デシリアライズ時に 400）</li>
 *   <li>{@code bgColor}: 必須・HEX カラーコード形式（{@code #RRGGBB}）</li>
 *   <li>{@code seasonalThemeId}: null 許容</li>
 *   <li>{@code hideChatPreview}: 必須・null 不可</li>
 * </ul>
 *
 * <p>Jackson デシリアライズのため {@code @NoArgsConstructor} と {@code @Setter} を付与する。
 * テスト等でのビルダー利用のため {@code @Builder} + {@code @AllArgsConstructor} も付与する。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateAppearanceRequest {

    /** テーマモード（必須）。 */
    @NotNull(message = "theme は必須です")
    private ThemeMode theme;

    /** 背景色 HEX コード（必須・#RRGGBB 形式）。 */
    @NotNull(message = "bgColor は必須です")
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "bgColor は #RRGGBB 形式で指定してください")
    private String bgColor;

    /** 季節テーマ ID（null 許容）。 */
    private Long seasonalThemeId;

    /** チャットプレビュー非表示フラグ（必須）。 */
    @NotNull(message = "hideChatPreview は必須です")
    private Boolean hideChatPreview;
}
