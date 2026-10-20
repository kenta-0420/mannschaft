package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のInteractionResult。BIGINTはdecimal string、日時はUTC瞬間。 */
public record InteractionResult(UUID commandId, UUID dinosaurId, String reactionKey, String affinityBand, boolean affinityChanged, Instant completedAt) { }
