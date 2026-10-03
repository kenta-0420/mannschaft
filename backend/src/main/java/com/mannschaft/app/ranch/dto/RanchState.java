package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のRanchState。BIGINTはdecimal string、日時はUTC瞬間。 */
public record RanchState(String featureStatus, boolean deliveryPaused, String rewardsStatus, boolean shopAvailable, CareBudget careBudget, OwnerSummary owner, DinosaurSummary dinosaur, RanchSettings settings, WeekBudget weekBudget, List<RoomSlotSummary> roomSlots, String policyVersion, Instant serverTime, AssignmentSummary assignment) { }
