package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のAssignmentResult。BIGINTはdecimal string、日時はUTC瞬間。 */
public record AssignmentResult(UUID dinosaurId, AssignmentMethod method, String speciesKey, String variantKey, Habitat habitat, String speciesCatalogVersion, Instant confirmedAt, String version) { }
