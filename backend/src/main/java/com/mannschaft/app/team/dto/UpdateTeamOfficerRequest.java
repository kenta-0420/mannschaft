package com.mannschaft.app.team.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チーム役員更新リクエスト DTO。
 * PATCH /teams/{id}/officers/{officerId}
 * 全フィールドは任意（null の場合は既存値を維持）。
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateTeamOfficerRequest {

    /** 役員名（任意・最大100文字）*/
    private String name;

    /** 役職名（任意・最大100文字）*/
    private String title;

    /** 表示フラグ（任意）*/
    @JsonProperty("is_visible")
    private Boolean isVisible;
}
