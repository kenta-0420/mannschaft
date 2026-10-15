package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * ディビジョンレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class DivisionResponse {

    private Long id;
    private Long tournamentId;
    private String name;
    private Integer level;
    private DivisionSlotsDto slots;
    private DivisionAuditDto audit;

    public record DivisionSlotsDto(
            Integer promotionSlots, Integer relegationSlots, Integer playoffPromotionSlots,
            Integer maxParticipants, Integer minEntryCount, Integer maxEntryCount,
            Integer sortOrder) {}

    public record DivisionAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
