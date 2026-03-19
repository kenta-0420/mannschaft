package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ユーザー権限グループ割当リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UserPermissionGroupAssignRequest {

    @NotNull
    private final List<Long> groupIds;
}
