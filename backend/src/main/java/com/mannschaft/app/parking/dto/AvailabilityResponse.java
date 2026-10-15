package com.mannschaft.app.parking.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 来場者用区画空き状況レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AvailabilityResponse {

    private final LocalDate date;
    private final List<SpaceAvailability> spaces;

    @Getter
    @RequiredArgsConstructor
    public static class SpaceAvailability {
        private final Long spaceId;
        private final String spaceNumber;
        private final boolean available;
    }
}
