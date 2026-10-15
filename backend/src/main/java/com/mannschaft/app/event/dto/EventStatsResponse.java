package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * イベント統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EventStatsResponse {

    private final long totalEvents;
    private final long draftEvents;
    private final long publishedEvents;
    private final long completedEvents;
    private final long cancelledEvents;
    private final long totalRegistrations;
    private final long approvedRegistrations;
    private final long totalCheckins;
}
