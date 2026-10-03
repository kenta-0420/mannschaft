package com.mannschaft.app.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * カレンダーレイヤー設定の部分更新リクエスト（F03.19 §4.4 / R2 裁定）。
 *
 * <p><b>部分更新セマンティクス</b>: 各項目の {@code null}（未指定を含む）は
 * 「変更しない」を意味する。全置換（PUT）にすると「色を変えただけで {@code hidden} が
 * 既定値へ巻き戻る」という P2 違反が起きるため、必ず PATCH の部分更新として扱う。</p>
 *
 * <p>「色を自動色へ戻す」は本リクエストでは表現しない（{@code color: null} は
 * 「変更しない」であり意味を二重に負わせない）。自動色へ戻すのは
 * {@code DELETE /me/calendar-layers/{scopeType}/{scopeId}} の役割である（§4.5）。</p>
 *
 * <p>record の正準コンストラクタ 1 本のみ（Jackson がパラメータ名でバインドできる形）。</p>
 */
@Schema(description = "カレンダーレイヤー設定の部分更新（null/未指定 = 変更しない）")
public record CalendarLayerUpdateRequest(

        @Schema(description = "ユーザー指定色（#RRGGBB。null/未指定 = 変更しない）",
                example = "#DC2626", nullable = true)
        String color,

        @Schema(description = "既定で非表示にするか（null/未指定 = 変更しない）",
                example = "true", nullable = true)
        Boolean hidden) {
}
