package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のOwnerSummary。BIGINTはdecimal string、日時はUTC瞬間。 */
public record OwnerSummary(UUID id, ParticipationStatus status, String balance, String version) { }
