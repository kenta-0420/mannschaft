package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 権限グループレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class PermissionGroupResponse {

    private final Long id;
    private final String name;
    private final String targetRole;
    private final List<String> permissions;
    private final LocalDateTime createdAt;
}
