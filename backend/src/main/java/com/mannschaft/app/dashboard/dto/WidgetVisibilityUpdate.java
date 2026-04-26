package com.mannschaft.app.dashboard.dto;

import com.mannschaft.app.dashboard.MinRole;

/**
 * F02.2.1: ダッシュボードウィジェット可視性設定の更新リクエスト1件分。
 *
 * <p>{@code PUT /widget-visibility} のリクエストボディ要素。Service 層に渡される
 * 暫定定義。B-3 部隊（Controller/Request DTO 担当）が外部 API 用 DTO を定義し、
 * 内部用にこの型へマップして Service を呼び出す想定。</p>
 *
 * @param widgetKey 更新対象のウィジェットキー
 * @param minRole   新しい最低必要ロール
 */
public record WidgetVisibilityUpdate(
        String widgetKey,
        MinRole minRole
) {
}
