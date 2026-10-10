package com.mannschaft.app.ranch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のDinosaurSummary。BIGINTはdecimal string、日時はUTC瞬間。 */
public record DinosaurSummary(UUID id, @Schema(nullable = true) String speciesKey,
                              @Schema(nullable = true) String variantKey, @Schema(nullable = true) Habitat habitat,
                              @Schema(nullable = true) String speciesCatalogVersion, DinosaurStage stage,
                              @Schema(nullable = true) String name, @Schema(nullable = true) Instant namedAt,
                              String xp, @Schema(nullable = true) String nextStageXp, String version,
                              @Schema(nullable = true) EggSummary egg, String affinityBand) { }
