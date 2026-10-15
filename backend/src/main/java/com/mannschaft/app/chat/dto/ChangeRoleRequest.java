package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メンバーロール変更リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ChangeRoleRequest {

    @NotNull
    private final String role;
}
