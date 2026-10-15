package com.mannschaft.app.committee.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * トークンによる招集受諾・辞退リクエスト DTO。
 */
@Getter
@Setter
@NoArgsConstructor
public class InviteByTokenRequest {

    /** 招集トークン */
    @NotBlank
    private String inviteToken;
}
