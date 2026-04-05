package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 参加チームレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ParticipantResponse {

    private final Long id;
    private final Long divisionId;
    private final Long teamId;
    private final Integer seed;
    private final String displayName;
    private final String status;
    private final LocalDateTime joinedAt;
}
