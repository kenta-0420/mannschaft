package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 出場メンバーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RosterResponse {

    private final Long id;
    private final Long matchId;
    private final Long participantId;
    private final Long userId;
    private final Boolean isStarter;
    private final Integer jerseyNumber;
    private final String position;
    private final LocalDateTime createdAt;
}
