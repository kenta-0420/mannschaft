package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * スコア入力・更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ScoreUpdateRequest {

    private final Integer homeScore;
    private final Integer awayScore;
    private final Integer homeExtraScore;
    private final Integer awayExtraScore;
    private final Integer homePenaltyScore;
    private final Integer awayPenaltyScore;
    private final String notes;

    @NotNull
    private final Long version;

    private final List<MatchSetRequest> sets;
}
