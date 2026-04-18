package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモ設定更新リクエスト（PATCH, UPSERT）。
 *
 * <p><b>Phase 3 追加フィールド</b>: default_post_team_id / default_category。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateActionMemoSettingsRequest {

    /** 省略時は変更しない */
    @JsonProperty("mood_enabled")
    private Boolean moodEnabled;

    /**
     * Phase 3: デフォルト投稿先チームID。
     * null = 変更しない。-1 などの特殊値は使わず、null = 変更なし。
     * 意図的に解除する場合は別途専用 API を設ける（将来）。
     */
    @JsonProperty("default_post_team_id")
    private Long defaultPostTeamId;

    /** Phase 3: デフォルトカテゴリ。省略時は変更しない。 */
    @JsonProperty("default_category")
    private ActionMemoCategory defaultCategory;
}
