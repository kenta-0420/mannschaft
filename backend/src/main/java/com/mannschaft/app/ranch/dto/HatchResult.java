package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のHatchResult。BIGINTはdecimal string、日時はUTC瞬間。 */
/** version は孵化時の owner 集約版を表す。再送でも当時の値を保持する。 */
public record HatchResult(UUID commandId, UUID dinosaurId, DinosaurStage stage, String name, Instant namedAt, Instant hatchedAt, String version) { }
