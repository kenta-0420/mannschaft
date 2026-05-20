package com.mannschaft.app.admin.dto;

/** メール outbox メトリクス API のレスポンス DTO (設計書 §6.2 / §10)。 */
public record EmailOutboxMetricsResponse(
        long queueDepthPending,
        long queueDepthSending,
        long queueDepthDeadLetter,
        long queueDepthFailed,
        long queueDepthCancelled,
        /** 直近 24h の成功率 (0.0〜1.0)。送信実績ゼロなら null。 */
        Double successRate24h,
        /** 最古の PENDING エントリの経過秒。PENDING ゼロなら null。 */
        Long oldestPendingAgeSeconds
) {}
