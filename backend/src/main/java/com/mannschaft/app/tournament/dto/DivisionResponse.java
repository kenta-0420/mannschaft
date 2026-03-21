package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ディビジョンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DivisionResponse {

    private final Long id;
    private final Long tournamentId;
    private final String name;
    private final Integer level;
    private final Integer promotionSlots;
    private final Integer relegationSlots;
    private final Integer playoffPromotionSlots;
    private final Integer maxParticipants;
    private final Integer sortOrder;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
