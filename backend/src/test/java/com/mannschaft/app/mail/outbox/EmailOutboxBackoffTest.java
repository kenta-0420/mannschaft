package com.mannschaft.app.mail.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmailOutboxEntity#applyBackoff(Throwable)} の遅延戦略テスト。
 *
 * <p>設計書 §7.3 のバックオフ表を厳密に検証する:</p>
 * <pre>
 *   retry=0 → +10s, retry=1 → +30s, retry=2 → +2m,
 *   retry=3 → +10m, retry=4 → +30m, retry=5 → +2h,
 *   retry=6 → DEAD_LETTER
 * </pre>
 */
@DisplayName("EmailOutboxEntity#applyBackoff 単体テスト")
class EmailOutboxBackoffTest {

    /** 許容誤差秒。LocalDateTime.now() の呼び出し差分を吸収。 */
    private static final long TOLERANCE_SECONDS = 5;

    private EmailOutboxEntity newPending(int initialRetryCount) {
        EmailOutboxEntity entity = EmailOutboxEntity.builder()
                .templateKind("VERIFICATION")
                .locale("ja")
                .toAddress(new byte[]{1, 2, 3})
                .toAddressHash(new byte[32])
                .sourceDomain("auth")
                .idempotencyKey("k" + initialRetryCount)
                .retryCount(initialRetryCount)
                .build();
        EmailOutboxEntity.prepareForEnqueue(entity);
        entity.markSending(); // SENDING 中に失敗するシナリオ想定
        return entity;
    }

    private void assertNextAttemptAround(LocalDateTime actual, long expectedSeconds) {
        LocalDateTime expected = LocalDateTime.now().plusSeconds(expectedSeconds);
        long diff = Math.abs(ChronoUnit.SECONDS.between(actual, expected));
        assertThat(diff)
                .as("nextAttemptAt should be around +%ds (diff=%ds)", expectedSeconds, diff)
                .isLessThanOrEqualTo(TOLERANCE_SECONDS);
    }

    @Test
    @DisplayName("retry=0 → 次回 +10s, status=PENDING, retry=1")
    void backoff_retry0() {
        EmailOutboxEntity e = newPending(0);
        e.applyBackoff(new RuntimeException("transient"));
        assertThat(e.getStatusAsEnum()).isEqualTo(EmailOutboxStatus.PENDING);
        assertThat(e.getRetryCount()).isEqualTo(1);
        assertNextAttemptAround(e.getNextAttemptAt(), 10);
    }

    @Test
    @DisplayName("retry=1 → 次回 +30s")
    void backoff_retry1() {
        EmailOutboxEntity e = newPending(1);
        e.applyBackoff(new RuntimeException("transient"));
        assertThat(e.getStatusAsEnum()).isEqualTo(EmailOutboxStatus.PENDING);
        assertThat(e.getRetryCount()).isEqualTo(2);
        assertNextAttemptAround(e.getNextAttemptAt(), 30);
    }

    @Test
    @DisplayName("retry=2 → 次回 +2分")
    void backoff_retry2() {
        EmailOutboxEntity e = newPending(2);
        e.applyBackoff(new RuntimeException("transient"));
        assertThat(e.getRetryCount()).isEqualTo(3);
        assertNextAttemptAround(e.getNextAttemptAt(), 120);
    }

    @Test
    @DisplayName("retry=3 → 次回 +10分")
    void backoff_retry3() {
        EmailOutboxEntity e = newPending(3);
        e.applyBackoff(new RuntimeException("transient"));
        assertThat(e.getRetryCount()).isEqualTo(4);
        assertNextAttemptAround(e.getNextAttemptAt(), 600);
    }

    @Test
    @DisplayName("retry=4 → まだ PENDING, 次回 +30分")
    void backoff_retry4_stillPending() {
        EmailOutboxEntity e = newPending(4);
        e.applyBackoff(new RuntimeException("transient"));
        assertThat(e.getStatusAsEnum()).isEqualTo(EmailOutboxStatus.PENDING);
        assertThat(e.getRetryCount()).isEqualTo(5);
        assertNextAttemptAround(e.getNextAttemptAt(), 1800);
    }

    @Test
    @DisplayName("retry=5 (最大失敗回数到達) → DEAD_LETTER に遷移")
    void backoff_retry5_deadLetter() {
        EmailOutboxEntity e = newPending(5);
        LocalDateTime beforeNext = e.getNextAttemptAt();
        e.applyBackoff(new RuntimeException("final transient"));
        assertThat(e.getStatusAsEnum()).isEqualTo(EmailOutboxStatus.DEAD_LETTER);
        assertThat(e.getRetryCount()).isEqualTo(6);
        // DEAD_LETTER 時は nextAttemptAt を更新しない (設計書 §5.再キューの注意点に倣う)
        assertThat(e.getNextAttemptAt()).isEqualTo(beforeNext);
    }

    @Test
    @DisplayName("applyBackoff は last_error にクラス名 + メッセージを記録する")
    void backoff_recordsLastError() {
        EmailOutboxEntity e = newPending(0);
        e.applyBackoff(new RuntimeException("network timeout"));
        assertThat(e.getLastError())
                .contains("RuntimeException")
                .contains("network timeout");
    }

    @Test
    @DisplayName("markDeadLetter は即 DEAD_LETTER に遷移する (永久失敗経路)")
    void markDeadLetter_transitions() {
        EmailOutboxEntity e = newPending(0);
        e.markDeadLetter(new RuntimeException("permanent rejection"));
        assertThat(e.getStatusAsEnum()).isEqualTo(EmailOutboxStatus.DEAD_LETTER);
        assertThat(e.getLastError()).contains("permanent rejection");
        // retry_count は加算されない
        assertThat(e.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("markPendingForRetry は status を PENDING に戻すが retry_count はリセットしない")
    void markPendingForRetry_preservesRetryCount() {
        EmailOutboxEntity e = newPending(5);
        e.applyBackoff(new RuntimeException("boom"));
        assertThat(e.getStatusAsEnum()).isEqualTo(EmailOutboxStatus.DEAD_LETTER);
        e.markPendingForRetry();
        assertThat(e.getStatusAsEnum()).isEqualTo(EmailOutboxStatus.PENDING);
        // retry_count はリセットしない (設計書 §5)
        assertThat(e.getRetryCount()).isEqualTo(6);
    }
}
