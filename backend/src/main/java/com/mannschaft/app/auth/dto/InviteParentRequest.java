package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者招待リクエスト。
 */
@Getter
public class InviteParentRequest {

    @NotBlank
    @Email
    private final String parentEmail;

    @JsonCreator
    public InviteParentRequest(@JsonProperty("parent_email") String parentEmail) {
        this.parentEmail = parentEmail;
    }
}
