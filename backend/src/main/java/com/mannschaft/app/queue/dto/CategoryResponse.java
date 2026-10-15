package com.mannschaft.app.queue.dto;

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
    private final String queueMode;
    private final String prefixChar;
    private final Short maxQueueSize;
    private final Short displayOrder;
    private final LocalDateTime createdAt;
}
