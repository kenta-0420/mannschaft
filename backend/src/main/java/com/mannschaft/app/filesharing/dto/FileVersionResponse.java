package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ファイルバージョンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FileVersionResponse {

    private final Long id;
    private final Long fileId;
    private final Integer versionNumber;
    private final String fileKey;
    private final Long fileSize;
    private final String contentType;
    private final Long uploadedBy;
    private final String comment;
    private final LocalDateTime createdAt;
}
