package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ブログシリーズレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BlogSeriesResponse {

    private final Long id;
    private final Long teamId;
    private final Long organizationId;
    private final String name;
    private final String description;
    private final Long createdBy;
    private final long postCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
