package com.mannschaft.app.team.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チームカスタムフィールド更新リクエスト DTO。
 * PATCH /teams/{id}/custom-fields/{fieldId}
 * 全フィールドは任意（null の場合は既存値を維持）。
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateTeamCustomFieldRequest {

    /** ラベル（任意・最大100文字）*/
    private String label;

    /** 値（任意・最大1000文字）*/
    private String value;

    /** 表示フラグ（任意）*/
    @JsonProperty("is_visible")
    private Boolean isVisible;
}
