package com.mannschaft.app.tournament.roster.dto;

import lombok.Builder;

import java.util.UUID;

/**
 * 試合メンバー表の選手 1 行（F08.7.1/05）。
 */
@Builder
public record RosterPlayerResponse(
        Long id,
        Long userId,
        String displayName,
        Boolean isStarter,
        Integer jerseyNumber,
        String position,
        String registrationNumber,
        UUID uniformSetId
) {
}
