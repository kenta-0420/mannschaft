package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 昇降格記録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PromotionRecordResponse {

    private final Long id;
    private final Long tournamentId;
    private final Long teamId;
    private final Long fromDivisionId;
    private final Long toDivisionId;
    private final String type;
    private final Integer finalRank;
    private final String reason;
    private final Long executedBy;
    private final LocalDateTime executedAt;
}
