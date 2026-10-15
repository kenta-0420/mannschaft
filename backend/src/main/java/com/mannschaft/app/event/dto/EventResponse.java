package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * イベント概要レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EventResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String slug;
    private final String subtitle;
    private final String coverImageKey;
    private final String status;
    private final String visibility;
    private final LocalDateTime registrationStartsAt;
    private final LocalDateTime registrationEndsAt;
    private final Integer maxCapacity;
    private final Integer registrationCount;
    private final Integer checkinCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
