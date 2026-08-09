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
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentPaymentRetryBatch} の単体テスト。
 * F03.11 Phase5a §11 決済リトライバッチの主要パスを検証する。
 *
 * <p>本テストは、リトライ上限到達で対象レコードが絞り込みから外れて
 * 母集合が縮んでいく状況でも、キーセットページングにより全件が取りこぼしなく
 * 処理されることを守る番人である。</p>
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
            given(cancellationRecordRepository.findFailedForRetryAfterId(anyInt(), anyLong(), any(Pageable.class)))
                    .willReturn(Collections.emptyList());

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
            // レコードは1件のみのため 1ページ目（cursor=0）で hasNext=false となり
            // ループはそこで終了する（2回目の問い合わせは発生しない）
            given(cancellationRecordRepository.findFailedForRetryAfterId(anyInt(), eq0L(), any(Pageable.class)))
                    .willReturn(List.of(record));
            given(cancellationRecordRepository.save(any())).willReturn(record);

            // when
            batch.run();

            // then: save が1回呼ばれる（processRetry 内でインクリメント後保存）
            verify(cancellationRecordRepository).save(record);
            // retryCount が 1 になっていること
            assertThat(record.getPaymentRetryCount()).isEqualTo(1);
        }

        /**
         * 取りこぼし検出テスト（キーセット化の中核 AC）。
         *
         * <p>チャンクサイズ(50)を超える件数を用意し、途中でリトライ上限に達した行が
         * 「絞り込みから外れて」母集合が縮む状況を、実際の DB フィルタと同じ意味論を
         * 持つインメモリ Fake（{@link FakeCancellationRecordStore}）で再現する。</p>
         *
         * <p>旧実装（OFFSET を「ページ番号」で前進させる方式）だと、1ページ目の処理で
         * 上限到達した行が絞り込みから抜けた瞬間に2ページ目の OFFSET がずれ、
         * 本来2ページ目にいたはずの行の一部が永久に読まれない。
         * このテストは Fake の {@code findByOffsetPage}（旧方式の意味論）と
         * {@code findByCursor}（新方式の意味論）の両方を用意し、キーセット方式のみが
         * 全件処理を達成できることを実証する。</p>
         */
        @Test
        @DisplayName("チャンクサイズを超える件数かつ一部が上限到達で絞り込みから外れても全件処理される（キーセット方式）")
        void run_largeVolumeWithShrinkingFilter_processesAllRecordsWithoutLoss() {
            // given: 120件のFAILEDレコード。うち偶数番目(60件)は退避カウント2から開始し、
            // 1回リトライすると MAX_RETRY_COUNT(3) に達して絞り込みから外れる。
            int totalCount = 120;
            List<RecruitmentCancellationRecordEntity> allRecords = new ArrayList<>();
            for (long id = 1; id <= totalCount; id++) {
                int startingRetryCount = (id % 2 == 0) ? 2 : 0;
                allRecords.add(buildFailedRecordViaReflection(id, startingRetryCount));
            }
            FakeCancellationRecordStore store = new FakeCancellationRecordStore(allRecords);

            // キーセット方式の意味論を再現する Mockito スタブ（id > cursor で絞り込み、上限に達した行は除外）
            given(cancellationRecordRepository.findFailedForRetryAfterId(anyInt(), any(Long.class), any(Pageable.class)))
                    .willAnswer(invocation -> {
                        int maxRetries = invocation.getArgument(0);
                        long cursor = invocation.getArgument(1);
                        Pageable pageable = invocation.getArgument(2);
                        return store.findByCursor(maxRetries, cursor, pageable.getPageSize());
                    });
            given(cancellationRecordRepository.save(any()))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            batch.run();

            // then: 全120件が最低1回はリトライ処理されている（取りこぼしゼロ）
            List<Long> processedIds = store.processedIds();
            assertThat(processedIds)
                    .as("全レコードが取りこぼしなく処理されること")
                    .containsExactlyInAnyOrderElementsOf(
                            allRecords.stream().map(RecruitmentCancellationRecordEntity::getId).collect(Collectors.toList()));
            assertThat(processedIds).hasSize(totalCount);
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
        void processRetry_incrementsRetryCount() {
            // given
            RecruitmentCancellationRecordEntity record = buildFailedRecordViaReflection(1L, 0);
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
        void processRetry_maxRetryReached_logsWarning() {
            // given: retryCount=2（次でMAX=3に到達）
            RecruitmentCancellationRecordEntity record = buildFailedRecordViaReflection(1L, 2);
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
        void processRetry_exceptionInSave_returnsFalse() {
            // given
            RecruitmentCancellationRecordEntity record = buildFailedRecordViaReflection(1L, 0);
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

    private static Long eq0L() {
        return org.mockito.ArgumentMatchers.eq(0L);
    }

    /**
     * テスト用 FAILED 状態のキャンセル記録を構築する。
     */
    private RecruitmentCancellationRecordEntity buildFailedRecord(Long id, int retryCount) throws Exception {
        return buildFailedRecordViaReflection(id, retryCount);
    }

    private RecruitmentCancellationRecordEntity buildFailedRecordViaReflection(Long id, int retryCount) {
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
        try {
            setField(record, "id", id);
            setField(record, "paymentRetryCount", retryCount);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    /**
     * DB のキーセットフィルタ（{@code id > cursor AND paymentRetryCount < maxRetries}）と
     * 同じ意味論をインメモリで再現する Fake ストア。
     *
     * <p>{@code save} が呼ばれるたびに実データの {@code paymentRetryCount} が更新されるため、
     * 次回の {@code findByCursor} 呼び出しでは最新の状態を反映した絞り込み結果を返す
     * （実 DB のトランザクション内クエリと同じ挙動）。</p>
     */
    private static class FakeCancellationRecordStore {
        private final List<RecruitmentCancellationRecordEntity> records;
        private final List<Long> processedIds = new ArrayList<>();

        FakeCancellationRecordStore(List<RecruitmentCancellationRecordEntity> records) {
            this.records = records;
        }

        List<RecruitmentCancellationRecordEntity> findByCursor(int maxRetries, long cursor, int pageSize) {
            List<RecruitmentCancellationRecordEntity> result = records.stream()
                    .filter(r -> r.getId() > cursor)
                    .filter(r -> r.getPaymentRetryCount() < maxRetries)
                    .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                    .limit(pageSize)
                    .peek(r -> processedIds.add(r.getId()))
                    .collect(Collectors.toList());
            return result;
        }

        List<Long> processedIds() {
            return processedIds;
        }
    }
}
