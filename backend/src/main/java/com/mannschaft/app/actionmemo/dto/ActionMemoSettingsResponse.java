package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモ設定レスポンス。
 *
 * <p>レコード未作成のユーザーはデフォルト値（mood_enabled = false）で返す。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ActionMemoSettingsResponse {

    @JsonProperty("mood_enabled")
    private boolean moodEnabled;
}
