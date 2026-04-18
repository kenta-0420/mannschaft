package com.mannschaft.app.event.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * RSVP集計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EventRsvpSummaryResponse {

    private final long attending;
    private final long notAttending;
    private final long maybe;
    private final long undecided;
    private final long total;
}
