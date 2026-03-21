package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フォルダレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FolderResponse {

    private final Long id;
    private final String scopeType;
    private final Long teamId;
    private final Long organizationId;
    private final Long userId;
    private final Long parentId;
    private final String name;
    private final String description;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
