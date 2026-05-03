package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモ設定更新リクエスト（PATCH, UPSERT）。
 *
 * <p><b>Phase 3 追加フィールド</b>: default_post_team_id / default_category。</p>
 * <p><b>Phase 4-β 追加フィールド</b>: reminder_enabled / reminder_time。</p>
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

    /** Phase 4-β: リマインド有効フラグ。省略時は変更しない。 */
    @JsonProperty("reminder_enabled")
    private Boolean reminderEnabled;

    /**
     * Phase 4-β: リマインド通知時刻（"HH:mm" 形式）。省略時は変更しない。
     * reminderEnabled = true の場合は必須。
     */
    @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$",
             message = "通知時刻は HH:mm 形式で入力してください（例: 09:00）")
    @JsonProperty("reminder_time")
    private String reminderTime;
}
