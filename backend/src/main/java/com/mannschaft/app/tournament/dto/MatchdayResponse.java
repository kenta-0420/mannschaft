package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 節レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MatchdayResponse {

    private final Long id;
    private final Long divisionId;
    private final String name;
    private final Integer matchdayNumber;
    private final LocalDate scheduledDate;
    private final String status;
    private final List<MatchResponse> matches;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
