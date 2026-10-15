package com.mannschaft.app.receipt.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * 但し書き候補レスポンスDTO。
 */
@Getter
@Builder
public class DescriptionSuggestionResponse {
    private final List<Suggestion> suggestions;
    private final String template;

    /**
     * 但し書き候補。
     */
    @Getter
    @Builder
    public static class Suggestion {
        private final String description;
        private final String source;
        private final Long scheduleId;
        private final String scheduleTitle;
        private final LocalDate scheduleDate;
        private final String paymentItemName;
    }
}
