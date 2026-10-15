package com.mannschaft.app.tournament.roster.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自チーム分の試合メンバー表（選手＋ベンチ役員＋締切・ロック状態／F08.7.1/05 §4）。
 */
@Builder
public record FixtureRosterResponse(
        Long matchId,
        Long participantId,
        Long teamId,
        LocalDateTime rosterDeadline,
        boolean locked,
        List<RosterPlayerResponse> players,
        List<RosterStaffResponse> staff
) {
}
