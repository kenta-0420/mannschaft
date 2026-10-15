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

    /** 空配列は対象スコープの全割当解除を表し、null は受け付けない。 */
    @NotNull
    private final List<Long> groupIds;
}
