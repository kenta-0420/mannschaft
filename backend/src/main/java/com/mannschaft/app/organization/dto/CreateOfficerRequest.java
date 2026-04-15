package com.mannschaft.app.organization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 組織役員作成リクエスト DTO。
 * POST /organizations/{id}/officers
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateOfficerRequest {

    /** 役員名（必須・最大100文字）*/
    @NotBlank
    private String name;

    /** 役職名（必須・最大100文字）*/
    @NotBlank
    private String title;

    /** 表示フラグ（デフォルト true）*/
    @JsonProperty("is_visible")
    private Boolean isVisible;
}
