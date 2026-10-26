package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageMeetupVoteType;
import jakarta.validation.constraints.NotNull;

/**
 * F17.1 Phase 3-β — 寄合投票リクエスト。
 *
 * <p>同一候補日に対する再投票は UPDATE 扱い（vote_type のみ変更）。</p>
 */
public record MeetupVoteRequest(
        @NotNull VillageMeetupVoteType voteType) {
}
