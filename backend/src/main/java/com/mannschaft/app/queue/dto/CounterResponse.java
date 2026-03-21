package com.mannschaft.app.queue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * カウンターレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CounterResponse {

    private final Long id;
    private final Long categoryId;
    private final String name;
    private final String description;
    private final String acceptMode;
    private final Short avgServiceMinutes;
    private final Boolean avgServiceMinutesManual;
    private final Short maxQueueSize;
    private final Boolean isActive;
    private final Boolean isAccepting;
    private final LocalTime operatingTimeFrom;
    private final LocalTime operatingTimeTo;
    private final Short displayOrder;
    private final Long createdBy;
    private final LocalDateTime createdAt;
}
