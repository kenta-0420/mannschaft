package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 権限レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PermissionResponse {

    private final Long id;
    private final String targetType;
    private final Long targetId;
    private final String permissionType;
    private final String permissionTargetType;
    private final Long permissionTargetId;
    private final LocalDateTime createdAt;
}
