package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetRetryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftBudgetRetryBatchJob} 単体テスト（Phase 10-β）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>PENDING を retry_count &lt; 3 まで再実行</li>
 *   <li>retry_count = 3 (上限到達済) は execute せずに即 EXHAUSTED 化</li>
 *   <li>executor 例外は continue で他レコード処理</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetRetryBatchJob 単体テスト")
class ShiftBudgetRetryBatchJobTest {

    @Mock
    private ShiftBudgetFailedEventRepository repository;
    @Mock
    private ShiftBudgetRetryExecutor executor;

    private ShiftBudgetRetryBatchJob job;

    @BeforeEach
    void setUp() {
        job = new ShiftBudgetRetryBatchJob(repository, executor);
    }

    private ShiftBudgetFailedEventEntity entity(int retryCount, ShiftBudgetFailedEventStatus status) {
        return ShiftBudgetFailedEventEntity.builder()
                .organizationId(1L)
                .eventType(ShiftBudgetFailedEventType.THRESHOLD_ALERT)
                .sourceId(42L)
                .payload("{}")
                .retryCount(retryCount)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("PENDING を retry_count < 3 で executor.execute へ流す")
    void run_PENDINGをexecuteへ() {
        ShiftBudgetFailedEventEntity e1 = entity(0, ShiftBudgetFailedEventStatus.PENDING);
        ShiftBudgetFailedEventEntity e2 = entity(1, ShiftBudgetFailedEventStatus.RETRYING);
        given(repository.findRetryablePending(any(), any(), any(Pageable.class)))
                .willReturn(List.of(e1, e2));
        given(executor.execute(any())).willReturn(true);

        job.run();

        verify(executor, times(2)).execute(any());
    }

    @Test
    @DisplayName("retry_count >= MAX_RETRY のレコードは execute せず EXHAUSTED 化")
    void run_上限到達は即EXHAUSTED化() {
        ShiftBudgetFailedEventEntity exhausted = entity(
                ShiftBudgetRetryExecutor.MAX_RETRY, ShiftBudgetFailedEventStatus.PENDING);
        given(repository.findRetryablePending(any(), any(), any(Pageable.class)))
                .willReturn(List.of(exhausted));

        job.run();

        // execute は呼ばれない
        verify(executor, never()).execute(any());
        // markFailed (maxRetry=0) で EXHAUSTED に遷移して save
        assertThat(exhausted.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.EXHAUSTED);
        verify(repository).save(exhausted);
    }

    @Test
    @DisplayName("executor 例外でも continue して他レコード処理")
    void run_executor例外でもcontinue() {
        ShiftBudgetFailedEventEntity e1 = entity(0, ShiftBudgetFailedEventStatus.PENDING);
        ShiftBudgetFailedEventEntity e2 = entity(0, ShiftBudgetFailedEventStatus.PENDING);
        given(repository.findRetryablePending(any(), any(), any(Pageable.class)))
                .willReturn(List.of(e1, e2));
        given(executor.execute(e1)).willThrow(new RuntimeException("DB lock timeout"));
        given(executor.execute(e2)).willReturn(true);

        // 例外を伝播させずに終了する
        job.run();

        verify(executor).execute(e1);
        verify(executor).execute(e2);
    }

    @Test
    @DisplayName("対象 0 件でも例外を投げない")
    void run_空でも正常終了() {
        given(repository.findRetryablePending(any(), any(), any(Pageable.class)))
                .willReturn(List.of());

        job.run();

        verify(executor, never()).execute(any());
    }

    @Test
    @DisplayName("100件枠: 非対応100件を退避した次走行で対応1件を取得する（呼出し側の境界UT）")
    void run_unsupportedHundredThenSupported() {
        LocalDateTime previousAttempt = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<ShiftBudgetFailedEventEntity> unsupported = IntStream.range(0, 100)
                .<ShiftBudgetFailedEventEntity>mapToObj(index -> entity(0, ShiftBudgetFailedEventStatus.PENDING).toBuilder()
                        .id((long) index + 1)
                        .eventType(index % 2 == 0 ? ShiftBudgetFailedEventType.CONSUMPTION_RECORD
                                : ShiftBudgetFailedEventType.CONSUMPTION_CANCEL)
                        .errorMessage("初回診断").lastRetriedAt(previousAttempt).build())
                .toList();
        ShiftBudgetFailedEventEntity supported = entity(0, ShiftBudgetFailedEventStatus.PENDING)
                .toBuilder().id(101L).build();
        given(repository.findRetryablePending(any(), any(), any(Pageable.class)))
                .willReturn(unsupported, List.of(supported));
        // このUTは取得枠と2走行の呼出しを測る。executorの実永続化契約は実DB ITが担う。
        given(executor.execute(any())).willAnswer(invocation -> {
            ShiftBudgetFailedEventEntity event = invocation.getArgument(0);
            if (event == supported) {
                event.markRetrying();
                event.markSucceeded();
                return true;
            }
            event.markExhausted();
            return false;
        });

        job.run();

        verify(executor, times(100)).execute(any());
        verify(executor, never()).execute(supported);
        assertThat(supported.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.PENDING);
        assertThat(supported.getRetryCount()).isZero();
        assertThat(supported.getLastRetriedAt()).isNull();
        for (ShiftBudgetFailedEventEntity event : unsupported) {
            assertThat(event.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.EXHAUSTED);
            assertThat(event.getErrorMessage()).isEqualTo("初回診断");
            assertThat(event.getRetryCount()).isZero();
            assertThat(event.getLastRetriedAt()).isEqualTo(previousAttempt);
        }
        List<ShiftBudgetFailedEventEntity> firstRun = unsupported.stream()
                .<ShiftBudgetFailedEventEntity>map(event -> event.toBuilder().build()).toList();

        job.run();

        verify(executor, times(101)).execute(any());
        verify(executor).execute(supported);
        assertThat(supported.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.SUCCEEDED);
        assertThat(supported.getRetryCount()).isEqualTo(1);
        assertThat(unsupported).usingRecursiveComparison().isEqualTo(firstRun);
        org.mockito.ArgumentCaptor<Pageable> pages = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findRetryablePending(any(), any(), pages.capture());
        assertThat(pages.getAllValues()).allSatisfy(page -> {
            assertThat(page.getPageNumber()).isZero();
            assertThat(page.getPageSize()).isEqualTo(100);
        });
    }
}
