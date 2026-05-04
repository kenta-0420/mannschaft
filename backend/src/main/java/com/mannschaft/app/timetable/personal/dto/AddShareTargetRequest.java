package com.mannschaft.app.timetable.personal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * F03.15 Phase 5 共有先追加リクエスト。
 */
public record AddShareTargetRequest(
        @NotNull
        @JsonProperty("team_id") Long teamId) {
}
