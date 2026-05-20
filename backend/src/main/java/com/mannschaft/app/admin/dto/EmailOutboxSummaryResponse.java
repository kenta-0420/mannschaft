package com.mannschaft.app.admin.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** メール outbox 一覧 API のレスポンス DTO (設計書 §6.2)。PII は含まない。 */
public record EmailOutboxSummaryResponse(
        UUID id,
        String status,
        String templateKind,
        String sourceDomain,
        String sourceEventId,
        String locale,
        int retryCount,
        LocalDateTime createdAt,
        LocalDateTime nextAttemptAt,
        LocalDateTime sentAt,
        String lastError
) {}
