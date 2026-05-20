package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意承認リクエスト。
 */
@Getter
public class ApproveConsentRequest {

    @NotBlank
    private final String token;

    @JsonCreator
    public ApproveConsentRequest(@JsonProperty("token") String token) {
        this.token = token;
    }
}
