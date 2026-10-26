package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意否認リクエスト。
 */
@Getter
public class RejectConsentRequest {

    @NotBlank
    private final String token;

    @JsonCreator
    public RejectConsentRequest(@JsonProperty("token") String token) {
        this.token = token;
    }
}
