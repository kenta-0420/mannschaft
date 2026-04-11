package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 行動メモ設定更新リクエスト（PATCH, UPSERT）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateActionMemoSettingsRequest {

    /** 省略時は変更しない */
    @JsonProperty("mood_enabled")
    private Boolean moodEnabled;
}
