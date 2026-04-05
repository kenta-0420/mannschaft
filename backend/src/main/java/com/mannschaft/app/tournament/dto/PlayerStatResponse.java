package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 個人成績レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PlayerStatResponse {

    private final Long id;
    private final Long matchId;
    private final Long participantId;
    private final Long userId;
    private final String statKey;
    private final Integer valueInt;
    private final BigDecimal valueDecimal;
    private final LocalTime valueTime;
}
