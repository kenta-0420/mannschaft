package com.mannschaft.app.analytics;

import com.mannschaft.app.analytics.dto.BackfillJobResponse;
import com.mannschaft.app.analytics.dto.BackfillRequest;
import com.mannschaft.app.analytics.service.AnalyticsBackfillRunner;
import com.mannschaft.app.analytics.service.AnalyticsBackfillService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskRejectedException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnalyticsBackfillService} 単体テスト。
 *
 * <p>Issue #2990 L4: 是正前は実行本体（{@code @Async protected executeAsync}）が同一クラス内にあり、
 * {@code startBackfill} がそれを無修飾で呼んでいたため {@code @Async} が失効して同期実行されていた。
 * 是正後は {@link AnalyticsBackfillRunner} へ委譲する。本テストは「別 Bean へ委譲していること」を
 * 検体として固定する。</p>
 *
 * <p>なお是正前の本テストは集計サービスをすべてモックしていたため、
 * 183日ぶんの処理が同期実行されていても {@code status="RUNNING"} が返り<b>緑のままだった</b>。
 * モックが実行経路を消していた例として付記する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnalyticsBackfillService 単体テスト")
class AnalyticsBackfillServiceTest {

    @Mock private AnalyticsBackfillRunner backfillRunner;

    private AnalyticsBackfillService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsBackfillService(backfillRunner);
        when(backfillRunner.tryAcquire()).thenReturn(true);
    }

    private static BackfillRequest request(LocalDate from, LocalDate to) {
        return new BackfillRequest(from, to, List.of(BackfillTarget.REVENUE, BackfillTarget.USERS));
    }

    @Nested
    @DisplayName("startBackfill")
    class StartBackfill {

        @Test
        @DisplayName("正常系: RUNNINGレスポンスを返し、実行は別 Bean へ委譲する")
        void testStartBackfill_正常開始() {
            BackfillRequest req = request(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

            BackfillJobResponse result = service.startBackfill(req);

            assertThat(result.getStatus()).isEqualTo("RUNNING");
            // 自己呼び出しではなく Runner（別 Bean）のプロキシ経由で起動していること
            verify(backfillRunner).executeAsync(any(BackfillRequest.class), anyString());
        }

        @Test
        @DisplayName("異常系: fromがtoより後で例外（ANALYTICS_005）")
        void testStartBackfill_fromがtoより後で例外() {
            BackfillRequest req = request(LocalDate.of(2026, 2, 10), LocalDate.of(2026, 2, 1));

            assertThatThrownBy(() -> service.startBackfill(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_005"));
            verify(backfillRunner, never()).executeAsync(any(), anyString());
        }

        @Test
        @DisplayName("異常系: 6ヶ月超過で例外（ANALYTICS_004）")
        void testStartBackfill_6ヶ月超過で例外() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            BackfillRequest req = request(from, from.plusDays(183)); // 184日

            assertThatThrownBy(() -> service.startBackfill(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_004"));
            verify(backfillRunner, never()).executeAsync(any(), anyString());
        }

        @Test
        @DisplayName("異常系: 同時実行で例外（ANALYTICS_003）")
        void testStartBackfill_同時実行で例外() {
            when(backfillRunner.tryAcquire()).thenReturn(false);
            BackfillRequest req = request(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertThatThrownBy(() -> service.startBackfill(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_003"));
            verify(backfillRunner, never()).executeAsync(any(), anyString());
        }

        @Test
        @DisplayName("正常系: 183日ちょうどで成功")
        void testStartBackfill_183日ちょうどで成功() {
            LocalDate from = LocalDate.of(2026, 1, 1);

            BackfillJobResponse result = service.startBackfill(request(from, from.plusDays(182)));

            assertThat(result.getStatus()).isEqualTo("RUNNING");
        }

        @Test
        @DisplayName("異常系: job-pool の投入拒否時は実行権を解放してから例外を送出する")
        void testStartBackfill_投入拒否で実行権を解放() {
            // job-pool は AbortPolicy。解放し損ねると以後ずっと ANALYTICS_003 になる。
            doThrow(new TaskRejectedException("pool saturated"))
                    .when(backfillRunner).executeAsync(any(BackfillRequest.class), anyString());
            BackfillRequest req = request(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

            assertThatThrownBy(() -> service.startBackfill(req))
                    .isInstanceOf(TaskRejectedException.class);

            verify(backfillRunner).release();
        }
    }
}
