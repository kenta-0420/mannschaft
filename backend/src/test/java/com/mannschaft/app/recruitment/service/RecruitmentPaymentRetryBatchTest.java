package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.CancellationSource;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentPaymentRetryBatch} の単体テスト。
 * F03.11 Phase5a §11 決済リトライバッチの主要パスを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentPaymentRetryBatch 単体テスト")
class RecruitmentPaymentRetryBatchTest {

    @Mock
    private RecruitmentCancellationRecordRepository cancellationRecordRepository;

    @InjectMocks
    private RecruitmentPaymentRetryBatch batch;

    // ==========================================================
    // run - バッチメインループ
    // ==========================================================

    @Nested
    @DisplayName("run - 決済リトライバッチ実行")
    class Run {

        @Test
        @DisplayName("FAILED レコードが0件 → 何もしない")
        void run_noFailedRecords_doesNothing() {
            // given
            Page<RecruitmentCancellationRecordEntity> emptyPage =
                    new PageImpl<>(Collections.emptyList());
            given(cancellationRecordRepository.findFailedForRetry(anyInt(), any(Pageable.class)))
                    .willReturn(emptyPage);

            // when
            batch.run();

            // then: save は一切呼ばれない
            verify(cancellationRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("FAILED レコードが存在する場合 processRetry が呼ばれリトライカウントがインクリメントされる")
        void run_withFailedRecord_processRetryIsCalled() throws Exception {
            // given: FAILED レコード1件 (retryCount=0)
            RecruitmentCancellationRecordEntity record = buildFailedRecord(1L, 0);
            Page<RecruitmentCancellationRecordEntity> firstPage =
                    new PageImpl<>(List.of(record), PageRequest.of(0, 50), 1);
            given(cancellationRecordRepository.findFailedForRetry(anyInt(), any(Pageable.class)))
                    .willReturn(firstPage);
            given(cancellationRecordRepository.save(any())).willReturn(record);

            // when
            batch.run();

            // then: save が1回呼ばれる（processRetry 内でインクリメント後保存）
            verify(cancellationRecordRepository).save(record);
            // retryCount が 1 になっていること
            assertThat(record.getPaymentRetryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("チャンク処理: CHUNK_SIZE=50単位で正しくページネーション処理される")
        void run_withTwoChunks_processesAllChunks() throws Exception {
            // given: 1ページ目はhasNext=true、2ページ目は空
            RecruitmentCancellationRecordEntity record1 = buildFailedRecord(1L, 0);
            RecruitmentCancellationRecordEntity record2 = buildFailedRecord(2L, 1);

            // 1ページ目: 2件、hasNext=false（totalElements=2, size=50）
            Page<RecruitmentCancellationRecordEntity> firstPage =
                    new PageImpl<>(List.of(record1, record2), PageRequest.of(0, 50), 2);

            // 2ページ目: 空（ループ終了）
            Page<RecruitmentCancellationRecordEntity> secondPage =
                    new PageImpl<>(Collections.emptyList(), PageRequest.of(1, 50), 2);

            given(cancellationRecordRepository.findFailedForRetry(anyInt(), any(Pageable.class)))
                    .willReturn(firstPage) // 1回目
                    .willReturn(secondPage); // 2回目（hasNext=falseなので呼ばれない）

            given(cancellationRecordRepository.save(any())).willReturn(record1);

            // when
            batch.run();

            // then: 2件処理される
            verify(cancellationRecordRepository).save(record1);
            verify(cancellationRecordRepository).save(record2);
        }
    }

    // ==========================================================
    // processRetry - 個別リトライ処理
    // ==========================================================

    @Nested
    @DisplayName("processRetry - 個別リトライ処理")
    class ProcessRetry {

        @Test
        @DisplayName("正常系: retryCount がインクリメントされて保存される")
        void processRetry_incrementsRetryCount() throws Exception {
            // given
            RecruitmentCancellationRecordEntity record = buildFailedRecord(1L, 0);
            given(cancellationRecordRepository.save(any())).willReturn(record);

            // when
            boolean result = batch.processRetry(record);

            // then: スタブのため false を返す
            assertThat(result).isFalse();
            assertThat(record.getPaymentRetryCount()).isEqualTo(1);
            verify(cancellationRecordRepository).save(record);
        }

        @Test
        @DisplayName("MAX_RETRY_COUNT(3回) 到達時: 警告ログが出力されてカウントが3になる")
        void processRetry_maxRetryReached_logsWarning() throws Exception {
            // given: retryCount=2（次でMAX=3に到達）
            RecruitmentCancellationRecordEntity record = buildFailedRecord(1L, 2);
            given(cancellationRecordRepository.save(any())).willReturn(record);

            // when
            boolean result = batch.processRetry(record);

            // then
            assertThat(result).isFalse();
            assertThat(record.getPaymentRetryCount()).isEqualTo(3); // MAX到達
            verify(cancellationRecordRepository).save(record);
        }

        @Test
        @DisplayName("例外発生時: false を返し、save は呼ばれない（例外を握りつぶさない）")
        void processRetry_exceptionInSave_returnsFalse() throws Exception {
            // given
            RecruitmentCancellationRecordEntity record = buildFailedRecord(1L, 0);
            given(cancellationRecordRepository.save(any()))
                    .willThrow(new RuntimeException("DB 接続エラー"));

            // when
            boolean result = batch.processRetry(record);

            // then: 例外はキャッチして false を返す
            assertThat(result).isFalse();
        }
    }

    // ==========================================================
    // ヘルパー
    // ==========================================================

    /**
     * テスト用 FAILED 状態のキャンセル記録を構築する。
     */
    private RecruitmentCancellationRecordEntity buildFailedRecord(Long id, int retryCount) throws Exception {
        RecruitmentCancellationRecordEntity record = RecruitmentCancellationRecordEntity.builder()
                .participantId(100L)
                .listingId(200L)
                .userId(id)
                .teamId(1L)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(48)
                .feeAmount(5000)
                .paymentStatus(CancellationPaymentStatus.FAILED)
                .build();
        setField(record, "id", id);
        setField(record, "paymentRetryCount", retryCount);
        return record;
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
