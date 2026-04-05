package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フィールド定義レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FieldResponse {

    private final Long id;
    private final Long teamId;
    private final Long organizationId;
    private final String fieldName;
    private final String fieldType;
    private final String options;
    private final Boolean isRequired;
    private final Integer sortOrder;
    private final Boolean isActive;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
