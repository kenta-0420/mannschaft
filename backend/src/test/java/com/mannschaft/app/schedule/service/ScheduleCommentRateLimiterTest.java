package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.ScheduleCommentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F03.16 予定コメント投稿のレート制限 単体テスト（試練・AC-32）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §10.2 / §9.4 AC-32。</p>
 *
 * <h2>{@code Clock} を差し替えて窓を制御する理由</h2>
 * <p>本番閾値（30回/分）のまま実時間で 31 回叩くテストは CI を flaky にする。
 * {@link MutableClock}（テスト内固定 {@link Clock} 実装）で時刻を明示的に進め、
 * 実時間の {@code sleep} に一切依存しない。</p>
 */
@DisplayName("F03.16 ScheduleCommentRateLimiter（§10.2・AC-32）")
class ScheduleCommentRateLimiterTest {

    private static final Long USER_ID = 1L;

    @Test
    @DisplayName("AC-32 30回目までは成功し、31回目は 429 SCHEDULE_COMMENT_012 で拒否される")
    void 境界_30回まで成功し31回目は拒否される() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        ScheduleCommentRateLimiter limiter = new ScheduleCommentRateLimiter(clock, 30, 60);

        for (int i = 0; i < 30; i++) {
            int attempt = i + 1;
            assertThatCode(() -> limiter.requireWithinLimit(USER_ID))
                    .as("%d 回目は上限(30)以内なので成功するはず", attempt)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.requireWithinLimit(USER_ID))
                .as("31回目は上限超過で 429 になるはず")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ScheduleCommentErrorCode.RATE_LIMITED.getCode()));
    }

    @Test
    @DisplayName("AC-32 Clock を61秒進めるとウィンドウが更新され、再び投稿できる（実時間の sleep に依存しない）")
    void ウィンドウ経過後は再び投稿できる() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        ScheduleCommentRateLimiter limiter = new ScheduleCommentRateLimiter(clock, 30, 60);

        for (int i = 0; i < 30; i++) {
            limiter.requireWithinLimit(USER_ID);
        }
        assertThatThrownBy(() -> limiter.requireWithinLimit(USER_ID)).isInstanceOf(BusinessException.class);

        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> limiter.requireWithinLimit(USER_ID))
                .as("61秒経過でスライディングウィンドウの先頭が窓外に出て再び投稿できるはず")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ユーザーごとに独立してカウントされる（他人の投稿数に巻き込まれない）")
    void ユーザー間で独立してカウントされる() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        ScheduleCommentRateLimiter limiter = new ScheduleCommentRateLimiter(clock, 2, 60);

        limiter.requireWithinLimit(1L);
        limiter.requireWithinLimit(1L);
        assertThatThrownBy(() -> limiter.requireWithinLimit(1L)).isInstanceOf(BusinessException.class);

        assertThatCode(() -> limiter.requireWithinLimit(2L))
                .as("別ユーザーは自分のカウントで独立して判定されるはず")
                .doesNotThrowAnyException();
    }

    /** テスト用の可変 {@link Clock}（固定 zone・{@code advance} で時刻を進める）。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
