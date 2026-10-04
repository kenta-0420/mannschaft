package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のDinosaurSummary。BIGINTはdecimal string、日時はUTC瞬間。 */
public record DinosaurSummary(UUID id, String speciesKey, String variantKey, Habitat habitat,
                              String speciesCatalogVersion, DinosaurStage stage, String name,
                              Instant namedAt, String xp, String nextStageXp, String version,
                              EggSummary egg, String affinityBand) { }
