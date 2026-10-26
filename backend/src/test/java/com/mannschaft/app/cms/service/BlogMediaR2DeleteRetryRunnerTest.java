package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryStatus;
import com.mannschaft.app.cms.repository.BlogMediaR2DeleteRetryRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

/**
 * {@link BlogMediaR2DeleteRetryRunner} の単体テスト（Issue #2601 別任務）。
 *
 * <p>受け入れ条件:
 * AC3 リトライで削除に成功すると SUCCEEDED になり、使用量が file_size 分だけ減る。
 * AC4 リトライで失敗すると attempt_count が増え、next_attempt_at が期待どおりのバックオフ時刻に進む。
 * AC5 上限回数に達すると ABANDONED になり、以後の実行で拾われない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogMediaR2DeleteRetryRunner 単体テスト")
class BlogMediaR2DeleteRetryRunnerTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 10, 3, 0, 0);

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private BlogMediaR2DeleteRetryRepository retryRepository;

    @Mock
    private StorageQuotaService storageQuotaService;

    private BlogMediaR2DeleteRetryRunner runner;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        runner = new BlogMediaR2DeleteRetryRunner(r2StorageService, retryRepository, storageQuotaService, fixedClock);
    }

    private BlogMediaR2DeleteRetryEntity buildRetry(int attemptCount) {
        LocalDateTime past = FIXED_NOW.minusHours(1);
        return BlogMediaR2DeleteRetryEntity.builder()
                .objectKey("blog/TEAM/8801/orphan.jpg")
                .objectKeyHash("dummyhash")
                .fileSize(4096L)
                .scopeType(StorageScopeType.TEAM.name())
                .scopeId("8801")
                .status(BlogMediaR2DeleteRetryStatus.PENDING)
                .attemptCount(attemptCount)
                .nextAttemptAt(past)
                .createdAt(past)
                .updatedAt(past)
                .build();
    }

    @Nested
    @DisplayName("retryOne - 成功パス")
    class Success {

        @Test
        @DisplayName("AC3: 削除成功でSUCCEEDEDになり、使用量がfile_size分だけ減る")
        void 削除成功でSUCCEEDEDかつ使用量減算() {
            // given
            BlogMediaR2DeleteRetryEntity retry = buildRetry(1);
            given(retryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            runner.retryOne(retry);

            // then
            assertThat(retry.getStatus()).isEqualTo(BlogMediaR2DeleteRetryStatus.SUCCEEDED);
            then(storageQuotaService).should().recordDeletion(
                    StorageScopeType.TEAM, 8801L, 4096L, StorageFeatureType.CMS,
                    BlogMediaService.REFERENCE_TYPE, null, null);
        }
    }

    @Nested
    @DisplayName("retryOne - 失敗パス（バックオフ）")
    class FailureBackoff {

        @Test
        @DisplayName("AC4: 1回目の失敗でattempt_countが1になりnext_attempt_atが1時間後に進む")
        void 初回失敗でバックオフ1時間() {
            // given
            BlogMediaR2DeleteRetryEntity retry = buildRetry(0);
            doThrow(new RuntimeException("R2接続エラー")).when(r2StorageService).delete(retry.getObjectKey());
            given(retryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            runner.retryOne(retry);

            // then
            assertThat(retry.getAttemptCount()).isEqualTo(1);
            assertThat(retry.getStatus()).isEqualTo(BlogMediaR2DeleteRetryStatus.PENDING);
            assertThat(retry.getNextAttemptAt()).isEqualTo(FIXED_NOW.plus(Duration.ofHours(1)));
            assertThat(retry.getLastError()).contains("R2接続エラー");
            then(storageQuotaService).should(never()).recordDeletion(any(), any(), any(Long.class), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC4: 2回目の失敗でattempt_countが2になりnext_attempt_atが6時間後に進む")
        void 二回目失敗でバックオフ6時間() {
            // given: 1回失敗済み（attemptCount=1）からの2回目失敗
            BlogMediaR2DeleteRetryEntity retry = buildRetry(1);
            doThrow(new RuntimeException("R2接続エラー")).when(r2StorageService).delete(retry.getObjectKey());
            given(retryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            runner.retryOne(retry);

            // then
            assertThat(retry.getAttemptCount()).isEqualTo(2);
            assertThat(retry.getNextAttemptAt()).isEqualTo(FIXED_NOW.plus(Duration.ofHours(6)));
        }

        @Test
        @DisplayName("AC5: 5回目の失敗で上限到達しABANDONEDになる")
        void 上限到達でABANDONED() {
            // given: 4回失敗済み（attemptCount=4）からの5回目失敗
            BlogMediaR2DeleteRetryEntity retry = buildRetry(4);
            doThrow(new RuntimeException("R2接続エラー")).when(r2StorageService).delete(retry.getObjectKey());
            given(retryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            runner.retryOne(retry);

            // then
            assertThat(retry.getAttemptCount()).isEqualTo(5);
            assertThat(retry.getStatus()).isEqualTo(BlogMediaR2DeleteRetryStatus.ABANDONED);
        }

        @Test
        @DisplayName("last_errorは500文字に切り詰められる")
        void lastErrorは500文字に切り詰められる() {
            // given
            BlogMediaR2DeleteRetryEntity retry = buildRetry(0);
            String longMessage = "E".repeat(600);
            doThrow(new RuntimeException(longMessage)).when(r2StorageService).delete(retry.getObjectKey());
            given(retryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            runner.retryOne(retry);

            // then
            assertThat(retry.getLastError()).hasSize(500);
        }
    }
}
