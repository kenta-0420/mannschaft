package com.mannschaft.app.moduleevent.api.dto;

import java.time.Instant;
import java.util.UUID;

/** 隔離された配信の運用情報。payloadと例外本文を公開しない。 */
public record ModuleEventQuarantineResponse(
        UUID publicationId,
        String eventType,
        String listenerId,
        Instant publicationDate,
        Instant lastAttemptAt,
        Instant quarantinedAt,
        int retryCount,
        String failureCode) {
}
