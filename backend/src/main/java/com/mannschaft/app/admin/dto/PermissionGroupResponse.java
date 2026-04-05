package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 権限グループレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PermissionGroupResponse {

    private final Long id;
    private final String name;
    private final String description;
    private final String scopeType;
    private final Long scopeId;
    private final List<String> permissions;
    private final Long memberCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
