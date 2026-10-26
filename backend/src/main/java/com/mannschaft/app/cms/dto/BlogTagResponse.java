package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ブログタグレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BlogTagResponse {

    private final Long id;
    private final String name;
    private final String color;
    private final Integer sortOrder;
    private final Integer postCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
