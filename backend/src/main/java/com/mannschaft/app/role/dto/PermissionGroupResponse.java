package com.mannschaft.app.role.dto;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 権限グループレスポンス。
 */
@Getter
public class PermissionGroupResponse {

    private final Long id;
    private final String name;
    private final String targetRole;
    private final List<String> permissions;
    private final LocalDateTime createdAt;
    /**
     * ADMIN 専用権限（MANAGE_ORG_AFFILIATION 等）を含むか。含むグループの更新・削除・割当は、
     * スコープ ADMIN 本人（SYSTEM_ADMIN 不可）だけができる。FE が操作の可否と理由の表示に使う。
     */
    private final boolean containsAdminOnlyPermission;

    public PermissionGroupResponse(Long id, String name, String targetRole, List<String> permissions,
                                   LocalDateTime createdAt) {
        this(id, name, targetRole, permissions, createdAt, false);
    }

    public PermissionGroupResponse(Long id, String name, String targetRole, List<String> permissions,
                                   LocalDateTime createdAt, boolean containsAdminOnlyPermission) {
        this.id = id;
        this.name = name;
        this.targetRole = targetRole;
        this.permissions = permissions;
        this.createdAt = createdAt;
        this.containsAdminOnlyPermission = containsAdminOnlyPermission;
    }
}
