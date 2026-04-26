package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.dashboard.MinRole;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * F02.2.1: ダッシュボードウィジェット可視性設定の1レコード分。
 *
 * <p>{@code GET /widget-visibility} レスポンス・{@code PUT /widget-visibility} 結果の要素として
 * 利用される。Service 層と Controller 層（B-3 担当）で共有される DTO の暫定定義。
 * フィールド名はシンプルに {@code widgetKey} / {@code minRole} / {@code isDefault} 等を採用し、
 * 必要に応じて B-3 部隊が JSON 直列化ルール（snake_case 変換）を上書きする。</p>
 *
 * @param widgetKey   ウィジェットキー（例: {@code TEAM_NOTICES}）
 * @param minRole     最低必要ロール
 * @param isDefault   true ならアプリ層デフォルト値（DB レコードなし）／false なら DB 明示設定
 * @param updatedById 最終更新者ユーザーID（{@code isDefault=true} のとき null）
 * @param updatedAt   最終更新日時（{@code isDefault=true} のとき null）
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WidgetVisibilityItem(
        String widgetKey,
        MinRole minRole,
        boolean isDefault,
        Long updatedById,
        LocalDateTime updatedAt
) {
}
