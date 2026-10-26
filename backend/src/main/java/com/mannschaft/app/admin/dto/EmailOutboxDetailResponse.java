package com.mannschaft.app.admin.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** メール outbox 詳細 API のレスポンス DTO。payload が復号されている (設計書 §6.2)。 */
public record EmailOutboxDetailResponse(
        UUID id,
        String status,
        String templateKind,
        String sourceDomain,
        String sourceEventId,
        String locale,
        String toAddress,           // 復号済メールアドレス
        Map<String, String> payloadVars, // 復号・パース済 payload JSON (null = 30日後パージ済)
        int retryCount,
        String sesMessageId,
        LocalDateTime createdAt,
        LocalDateTime nextAttemptAt,
        LocalDateTime sentAt,
        String lastError
) {}
