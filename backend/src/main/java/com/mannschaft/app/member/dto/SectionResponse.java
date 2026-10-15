package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * セクションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SectionResponse {

    private final Long id;
    private final Long teamPageId;
    private final String sectionType;
    private final String title;
    private final String content;
    private final String imageS3Key;
    private final String imageCaption;
    private final Integer sortOrder;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
