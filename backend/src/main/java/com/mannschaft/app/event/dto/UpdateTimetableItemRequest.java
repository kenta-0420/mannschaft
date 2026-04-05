package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * タイムテーブル項目更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTimetableItemRequest {

    @Size(max = 200)
    private final String title;

    @Size(max = 500)
    private final String description;

    @Size(max = 100)
    private final String speaker;

    private final LocalDateTime startAt;

    private final LocalDateTime endAt;

    @Size(max = 200)
    private final String location;

    private final Integer sortOrder;
}
