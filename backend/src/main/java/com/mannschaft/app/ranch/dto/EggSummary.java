package com.mannschaft.app.ranch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のEggSummary。BIGINTはdecimal string、日時はUTC瞬間。 */
public record EggSummary(Instant startedAt, Instant readyAt, EggCrackStage crackStage, boolean hatchReady,
                         @Schema(nullable = true) Instant hatchedAt) { }
