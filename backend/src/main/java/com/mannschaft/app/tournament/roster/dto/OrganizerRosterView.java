package com.mannschaft.app.tournament.roster.dto;

import lombok.Builder;

import java.util.List;

/**
 * 主催者ビュー: 参加チーム単位の提出状況・内容（F08.7.1/05 §4 GET rosters）。
 */
@Builder
public record OrganizerRosterView(
        Long participantId,
        Long teamId,
        String teamDisplayName,
        boolean submitted,
        int playerCount,
        int staffCount,
        List<RosterPlayerResponse> players,
        List<RosterStaffResponse> staff
) {
}
