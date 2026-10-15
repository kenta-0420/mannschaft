package com.mannschaft.app.gallery.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * アルバムレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AlbumResponse {

    private final Long id;
    private final Long teamId;
    private final Long organizationId;
    private final String title;
    private final String description;
    private final Long coverPhotoId;
    private final LocalDate eventDate;
    private final String visibility;
    private final Boolean allowMemberUpload;
    private final Boolean allowDownload;
    private final Integer photoCount;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
