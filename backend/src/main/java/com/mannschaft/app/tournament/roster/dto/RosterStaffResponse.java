package com.mannschaft.app.tournament.roster.dto;

import lombok.Builder;

import java.util.UUID;

/**
 * 試合メンバー表のベンチ入り役員 1 行（F08.7.1/05 §8.3）。
 */
@Builder
public record RosterStaffResponse(
        UUID id,
        String role,
        String name,
        Long userId
) {
}
