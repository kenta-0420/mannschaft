package com.mannschaft.app.chart.batch;

import com.mannschaft.app.chart.repository.ChartRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ChartPurgeBackfillBatchService} 単体テスト（Phase D-5）。
 *
 * <p>Repository 呼び出しの委譲・例外伝搬を Mockito で検証する。
 * 実 DB に対する補正挙動の検証は別途 IntegrationTest で実施予定。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChartPurgeBackfillBatchService 単体テスト")
class ChartPurgeBackfillBatchServiceTest {

    @Mock
    private ChartRecordRepository chartRecordRepository;

    @InjectMocks
    private ChartPurgeBackfillBatchService batch;

    @Test
    @DisplayName("backfill: ChartRecordRepository#anonymizeOrphanCustomerUserId を 1 回呼ぶ")
    void backfill_invokes_repository_once() {
        given(chartRecordRepository.anonymizeOrphanCustomerUserId()).willReturn(5);

        batch.backfill();

        verify(chartRecordRepository, times(1)).anonymizeOrphanCustomerUserId();
    }

    @Test
    @DisplayName("backfill: 孤児 N 件のとき N を補正して正常終了する")
    void backfill_fixes_orphans() {
        int expectedFixed = 3;
        given(chartRecordRepository.anonymizeOrphanCustomerUserId()).willReturn(expectedFixed);

        // 例外なく完了することを確認
        batch.backfill();

        verify(chartRecordRepository, times(1)).anonymizeOrphanCustomerUserId();
    }

    @Test
    @DisplayName("backfill: 孤児 0 件のとき何も起きず正常終了する")
    void backfill_zero_orphans_no_exception() {
        given(chartRecordRepository.anonymizeOrphanCustomerUserId()).willReturn(0);

        batch.backfill();

        verify(chartRecordRepository, times(1)).anonymizeOrphanCustomerUserId();
    }

    @Test
    @DisplayName("backfill: Repository が例外を投げたら呼び出し元に伝搬する")
    void backfill_propagates_exception() {
        given(chartRecordRepository.anonymizeOrphanCustomerUserId())
                .willThrow(new RuntimeException("DB unreachable"));

        try {
            batch.backfill();
            fail("例外が伝搬されるはず");
        } catch (RuntimeException e) {
            // 例外を握り潰さず伝搬することを確認（ShedLock によるリトライを妨げない）
            assertThat(e.getMessage()).isEqualTo("DB unreachable");
        }
        verify(chartRecordRepository, times(1)).anonymizeOrphanCustomerUserId();
    }
}
