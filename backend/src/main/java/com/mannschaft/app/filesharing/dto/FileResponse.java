package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ファイルレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FileResponse {

    private final Long id;
    private final Long folderId;
    private final String name;
    private final String fileKey;
    private final Long fileSize;
    private final String contentType;
    private final String description;
    private final Long createdBy;
    private final Integer currentVersion;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
