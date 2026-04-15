package com.mannschaft.app.team.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チームカスタムフィールド作成リクエスト DTO。
 * POST /teams/{id}/custom-fields
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateTeamCustomFieldRequest {

    /** ラベル（必須・最大100文字）*/
    private String label;

    /** 値（必須・最大1000文字）*/
    private String value;

    /** 表示フラグ（デフォルト true）*/
    @JsonProperty("is_visible")
    private Boolean isVisible;
}
