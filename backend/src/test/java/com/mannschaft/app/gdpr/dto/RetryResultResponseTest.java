package com.mannschaft.app.gdpr.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 受付と完了の公開形、および既存同期呼出の後方互換を検証する。配送やDBの証明とは区別する。 */
class RetryResultResponseTest {
    @Test
    void synchronousConstructorKeepsQueuedFalse() {
        var result = new RetryResultResponse(true, "role", "SUCCESS", 1, "retry 成功");
        assertThat(result.queued()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void acceptedDiagnosisIsPendingAndNotSuccessful() {
        var result = new RetryResultResponse(false, "diagnosis", "PENDING", 1, "削除完了待ち", true);
        assertThat(result.queued()).isTrue();
        assertThat(result.succeeded()).isFalse();
        assertThat(result.newStatus()).isEqualTo("PENDING");
    }
}
