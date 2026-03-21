package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * セット別スコアリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class MatchSetRequest {

    @NotNull
    private final Integer setNumber;

    @NotNull
    private final Integer homeScore;

    @NotNull
    private final Integer awayScore;
}
