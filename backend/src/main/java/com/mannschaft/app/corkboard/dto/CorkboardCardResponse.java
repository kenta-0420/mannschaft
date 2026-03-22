package com.mannschaft.app.corkboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * コルクボードカードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CorkboardCardResponse {

    private final Long id;
    private final Long corkboardId;
    private final String cardType;
    private final String referenceType;
    private final Long referenceId;
    private final String contentSnapshot;
    private final String title;
    private final String body;
    private final String url;
    private final String ogTitle;
    private final String ogImageUrl;
    private final String ogDescription;
    private final String colorLabel;
    private final String cardSize;
    private final Integer positionX;
    private final Integer positionY;
    private final Integer zIndex;
    private final String userNote;
    private final LocalDateTime autoArchiveAt;
    private final Boolean isArchived;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
