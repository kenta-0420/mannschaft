package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * TODO カスタムステータスラベルレスポンス DTO（F02.3.1）。
 */
@Getter
@RequiredArgsConstructor
public class TodoStatusLabelResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String bucket;
    private final String color;
    private final Integer sortOrder;
    private final Boolean isSystemDefault;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
