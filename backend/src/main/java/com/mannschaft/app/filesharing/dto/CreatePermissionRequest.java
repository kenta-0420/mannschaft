package com.mannschaft.app.filesharing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 権限作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreatePermissionRequest {

    @NotBlank
    private final String targetType;

    @NotNull
    private final Long targetId;

    @NotBlank
    private final String permissionType;

    @NotBlank
    private final String permissionTargetType;

    @NotNull
    private final Long permissionTargetId;
}
