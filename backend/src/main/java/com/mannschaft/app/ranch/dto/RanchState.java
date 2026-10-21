package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/** 03 API正本のRanchState。BIGINTはdecimal string、日時はUTC瞬間。 */
public record RanchState(String featureStatus, boolean deliveryPaused, String rewardsStatus, boolean shopAvailable,
        @Schema(nullable = true) CareBudget careBudget,
        @Schema(nullable = true) OwnerSummary owner,
        @Schema(nullable = true) DinosaurSummary dinosaur,
        @Schema(nullable = true) RanchSettings settings,
        @Schema(nullable = true) WeekBudget weekBudget,
        List<RoomSlotSummary> roomSlots,
        @Schema(nullable = true) String policyVersion,
        Instant serverTime,
        @Schema(nullable = true) AssignmentSummary assignment) { }
