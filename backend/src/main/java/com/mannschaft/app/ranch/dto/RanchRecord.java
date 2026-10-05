package com.mannschaft.app.ranch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** 本人台帳の安全な表示形。source本文や診断・出生入力は含めない。 */
public record RanchRecord(UUID id, String kind, @Schema(nullable = true) String sourceType,
                          String deltaPoints, String deltaXp,
                          Instant occurredAt, @Schema(nullable = true) SourceLink sourceLink) {
    public record SourceLink(String kind, String id, String url) { }
}
