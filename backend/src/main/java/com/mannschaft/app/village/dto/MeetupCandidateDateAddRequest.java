package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * F17.1 Phase 3-β — 寄合候補日追加リクエスト。
 */
public record MeetupCandidateDateAddRequest(
        @NotNull LocalDate candidateDate,
        Integer sortOrder) {
}
