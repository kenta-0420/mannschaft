package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモ設定レスポンス。
 *
 * <p>レコード未作成のユーザーはデフォルト値（mood_enabled = false）で返す。</p>
 *
 * <p><b>Phase 3 追加フィールド</b>: default_post_team_id / default_category。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionMemoSettingsResponse {

    @JsonProperty("mood_enabled")
    private boolean moodEnabled;

    /** Phase 3: デフォルト投稿先チームID。NULL = 未設定 */
    @JsonProperty("default_post_team_id")
    private Long defaultPostTeamId;

    /** Phase 3: デフォルトカテゴリ */
    @JsonProperty("default_category")
    @Builder.Default
    private ActionMemoCategory defaultCategory = ActionMemoCategory.PRIVATE;
}
