package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * F17.1 Phase 3-β — 寄合候補日追加リクエスト。
 *
 * <p>{@code candidateTime} は任意（NULL は終日）。（#2357）</p>
 */
public record MeetupCandidateDateAddRequest(
        @NotNull LocalDate candidateDate,
        LocalTime candidateTime,
        Integer sortOrder) {
}
