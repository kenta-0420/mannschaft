package com.mannschaft.app.reflection.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.reflection.RecallSelfRating;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 想起履歴レスポンス（F06.5・§7 #11）。
 */
@Builder
public record RecallAttemptResponse(
        String id,
        String entryId,
        LocalDate recallDate,
        JsonNode recalledContent,
        RecallSelfRating selfRating,
        LocalDateTime revealedAt,
        LocalDateTime createdAt
) {
}
