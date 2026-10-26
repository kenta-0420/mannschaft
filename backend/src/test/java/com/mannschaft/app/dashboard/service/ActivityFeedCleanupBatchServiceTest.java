package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * activity_feed 30日削除バッチの制御構造テスト（F03.18 §10.5・AC-26）。
 *
 * <p>閾値より古い行のみが物理削除されることの実証は
 * {@code ActivityFeedCleanupBatchRepositoryIT} が実 MySQL で検証する。本クラスは
 * 「バッチが 30 日閾値で {@code deleteByCreatedAtBefore} を1回呼ぶこと」を
 * Mockito で固定する（{@code BlogScheduledPublishBatchServiceTest} の作法を踏襲）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("activity_feed 30日削除バッチ 制御構造テスト（F03.18 AC-26）")
class ActivityFeedCleanupBatchServiceTest {

    @Mock
    private ActivityFeedRepository activityFeedRepository;

    /**
     * 固定した壁時計 Clock。本番では {@code ClockConfig#wallClock}（業務ローカル時刻ゾーン）が
     * 注入される。ゾーンずれを検出できるよう、あえて UTC 以外のゾーンで固定する。
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-19T01:23:45Z");
    private static final ZoneId WALL_ZONE = ZoneId.of("Asia/Tokyo");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, WALL_ZONE);

    private ActivityFeedCleanupBatchService batchService;

    private void setUp() {
        batchService = new ActivityFeedCleanupBatchService(activityFeedRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("AC-26: 30日閾値で deleteByCreatedAtBefore を1回呼ぶ")
    void cleanupOldActivityFeed_calls_deleteByCreatedAtBefore_withThirtyDayThreshold() {
        setUp();
        given(activityFeedRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).willReturn(3);

        batchService.cleanupOldActivityFeed();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(activityFeedRepository, times(1)).deleteByCreatedAtBefore(captor.capture());
        LocalDateTime expectedThreshold = LocalDateTime.ofInstant(FIXED_INSTANT, WALL_ZONE).minusDays(30);
        assertThat(captor.getValue())
                .as("閾値は注入した壁時計 Clock のゾーンで解釈した現在時刻から30日前である"
                        + "（UTC 固定の utcClock を取り違えると JST 環境で 9 時間ずれる）")
                .isEqualTo(expectedThreshold);
    }

    @Test
    @DisplayName("AC-26: 削除件数0でも例外を投げない")
    void cleanupOldActivityFeed_noRowsToDelete_doesNotThrow() {
        setUp();
        given(activityFeedRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).willReturn(0);

        assertThatCode(() -> batchService.cleanupOldActivityFeed()).doesNotThrowAnyException();
        verify(activityFeedRepository, times(1)).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }
}
