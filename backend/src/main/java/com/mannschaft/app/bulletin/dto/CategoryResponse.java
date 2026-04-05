package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * カテゴリレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CategoryResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String description;
    private final Integer displayOrder;
    private final String color;
    private final String postMinRole;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
