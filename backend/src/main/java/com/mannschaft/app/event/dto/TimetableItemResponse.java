package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * タイムテーブル項目レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TimetableItemResponse {

    private final Long id;
    private final Long eventId;
    private final String title;
    private final String description;
    private final String speaker;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final String location;
    private final Integer sortOrder;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
