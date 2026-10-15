package com.mannschaft.app.organization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 組織役員更新リクエスト DTO。
 * PATCH /organizations/{id}/officers/{officerId}
 * 全フィールドは任意（null の場合は既存値を維持）。
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateOfficerRequest {

    /** 役員名（任意・最大100文字）*/
    private String name;

    /** 役職名（任意・最大100文字）*/
    private String title;

    /** 表示フラグ（任意）*/
    @JsonProperty("is_visible")
    private Boolean isVisible;
}
