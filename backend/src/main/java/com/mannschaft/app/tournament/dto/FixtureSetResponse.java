package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セット別スコアレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FixtureSetResponse {

    private final Long id;
    private final Integer setNumber;
    private final Integer homeScore;
    private final Integer awayScore;
}
