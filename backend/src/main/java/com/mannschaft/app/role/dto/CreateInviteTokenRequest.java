package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 招待トークン作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateInviteTokenRequest {

    @NotNull
    private final Long roleId;

    /** 有効期限: "1d","7d","30d","90d",null=無制限 */
    private final String expiresIn;

    /** 最大使用回数: null=無制限 */
    private final Integer maxUses;
}
