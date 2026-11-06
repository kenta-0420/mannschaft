package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のFeedingResult。BIGINTはdecimal string、日時はUTC瞬間。 */
public record FeedingResult(UUID commandId, UUID dinosaurId, String careKind, String costPoints, String gainedXp, boolean isGrowthCapped, DinosaurStage stageBefore, DinosaurStage stageAfter, String balanceAfter, String ruleVersion, Instant completedAt) { }
