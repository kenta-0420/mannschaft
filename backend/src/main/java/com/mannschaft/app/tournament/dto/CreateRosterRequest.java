package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 出場メンバー一括登録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateRosterRequest {

    @NotNull
    private final List<RosterEntry> entries;

    @Getter
    @RequiredArgsConstructor
    public static class RosterEntry {
        @NotNull
        private final Long participantId;

        @NotNull
        private final Long userId;

        private final Boolean isStarter;
        private final Integer jerseyNumber;

        @Size(max = 30)
        private final String position;
    }
}
