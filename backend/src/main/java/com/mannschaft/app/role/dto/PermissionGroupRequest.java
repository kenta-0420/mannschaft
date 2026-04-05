package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 権限グループ作成・更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class PermissionGroupRequest {

    @NotBlank
    private final String name;

    @NotBlank
    private final String targetRole;

    @NotEmpty
    private final List<Long> permissionIds;
}
