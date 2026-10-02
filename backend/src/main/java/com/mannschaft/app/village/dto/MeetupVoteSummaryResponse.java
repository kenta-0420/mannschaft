package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 寄合投票集計レスポンス。
 *
 * <p>寄合の各候補日に対する AVAILABLE / MAYBE / UNAVAILABLE の集計値を返す。</p>
 */
@Builder
public record MeetupVoteSummaryResponse(
        UUID meetupId,
        List<CandidateDateSummary> candidates) {

    @Builder
    public record CandidateDateSummary(
            UUID candidateDateId,
            LocalDate candidateDate,
            LocalTime candidateTime,
            int availableCount,
            int maybeCount,
            int unavailableCount) {
    }
}
