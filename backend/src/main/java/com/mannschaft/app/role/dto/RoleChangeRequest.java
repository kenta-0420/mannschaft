package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ロール変更リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class RoleChangeRequest {

    @NotNull
    private final Long roleId;
}
