package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.SettleCancellationFeeOutcome;
import com.mannschaft.app.payment.escrow.SettleCancellationFeeResult;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.CancellationSource;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * F03.11.1 リトライバッチが実決済へ結線されていることの試練（設計書 §5.5）。
 *
 * <p>受け入れ条件 AC-5（スタブでない）・AC-15（上限の境界）・AC-21（キーセットページング維持）を担う。</p>
 *
 * <p>バッチの依存は第四陣で 1 つ増える見込みのため、宣言済みコンストラクタを型で解決して組み立てる。
 * これによりシグネチャが変わってもテスト側の書き換えを要さない。</p>
 *
 * <p><b>AC-21 の限界（検分への申し送り）</b>: 設計書 §5.5・§12-14 は escrow のチャンク単位一括取得を
 * バッチ側の責務として書く一方、§3.4 は recruitment から escrow を読むことを禁じている。両立する API が
 * 設計書に定義されていないため、本テストは「キーセットページングが維持されること」と
 * 「レコード 1 件あたりの徴収呼び出しが 1 回を超えないこと」までを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F03.11.1 RecruitmentPaymentRetryBatch 実決済結線 試練")
class RecruitmentPaymentRetryBatchSettlementTest {

    @Mock private RecruitmentCancellationRecordRepository cancellationRecordRepository;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private RecruitmentCancellationFeeRetryProcessor retryProcessor;

    private static final int MAX_RETRY_COUNT = 3;

    @Test
    @DisplayName("AC-5: バッチを走らせると実際に決済 API が呼ばれる（リトライ回数を増やして常に false のスタブではない）")
    void ac5_batchReachesTheRealSettlementApi() {
        RecruitmentCancellationRecordEntity record = failedRecord(1L, 0);
        givenChunks(List.of(record));
        // 実処理は別 Bean へ委譲されるが、いずれの経路でも最終的に決済 API へ到達しなければならない。
        given(retryProcessor.processChunk(any())).willAnswer(invocation -> {
            List<RecruitmentCancellationRecordEntity> chunk = invocation.getArgument(0);
            for (RecruitmentCancellationRecordEntity r : chunk) {
                connectChargeService.settleCancellationFee(
                        EscrowSourceKind.RECRUITMENT, r.getListingId(), r.getParticipantId(),
                        r.getFeeAmount(), String.valueOf(r.getId()));
            }
            return 0;
        });
        given(connectChargeService.settleCancellationFee(
                any(EscrowSourceKind.class), anyLong(), anyLong(), anyLong(), anyString()))
                .willReturn(new SettleCancellationFeeResult(
                        SettleCancellationFeeOutcome.NOT_COLLECTIBLE, null, 3_000L));

        newBatch().run();

        verify(connectChargeService).settleCancellationFee(
                EscrowSourceKind.RECRUITMENT, 100L, record.getParticipantId(), 3_000L, "1");
    }

    @Test
    @DisplayName("AC-15: retryCount がちょうど3の行は対象外・2の行は対象（上限の境界）")
    void ac15_retryCountBoundary() {
        RecruitmentCancellationRecordEntity twice = failedRecord(1L, 2);
        RecruitmentCancellationRecordEntity exhausted = failedRecord(2L, 3);
        FakeStore store = new FakeStore(List.of(twice, exhausted));
        given(cancellationRecordRepository.findFailedForRetryAfterId(anyInt(), anyLong(), any(Pageable.class)))
                .willAnswer(invocation -> store.findByCursor(
                        invocation.getArgument(0), invocation.getArgument(1),
                        ((Pageable) invocation.getArgument(2)).getPageSize()));
        given(retryProcessor.processChunk(any())).willReturn(0);

        newBatch().run();

        ArgumentCaptor<Integer> maxRetries = ArgumentCaptor.forClass(Integer.class);
        verify(cancellationRecordRepository, atLeastOnce())
                .findFailedForRetryAfterId(maxRetries.capture(), anyLong(), any(Pageable.class));
        assertThat(maxRetries.getAllValues()).containsOnly(MAX_RETRY_COUNT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RecruitmentCancellationRecordEntity>> chunk =
                ArgumentCaptor.forClass(List.class);
        verify(retryProcessor).processChunk(chunk.capture());
        assertThat(chunk.getValue().stream().map(RecruitmentCancellationRecordEntity::getId).toList())
                .as("retryCount=2 は対象・retryCount=3 は対象外")
                .containsExactly(1L);
    }

    @Test
    @DisplayName("AC-21: キーセットページングを維持する（カーソルが前進し、母集合が縮んでも読み飛ばさない）")
    void ac21_keysetPagingIsMaintained() {
        List<RecruitmentCancellationRecordEntity> all = new ArrayList<>();
        for (long id = 1; id <= 120; id++) {
            // 偶数 ID は 1 回のリトライで上限に達し、絞り込みから外れて母集合が縮む。
            all.add(failedRecord(id, (id % 2 == 0) ? 2 : 0));
        }
        FakeStore store = new FakeStore(all);
        given(cancellationRecordRepository.findFailedForRetryAfterId(anyInt(), anyLong(), any(Pageable.class)))
                .willAnswer(invocation -> store.findByCursor(
                        invocation.getArgument(0), invocation.getArgument(1),
                        ((Pageable) invocation.getArgument(2)).getPageSize()));
        given(retryProcessor.processChunk(any())).willAnswer(invocation -> {
            List<RecruitmentCancellationRecordEntity> chunk = invocation.getArgument(0);
            chunk.forEach(RecruitmentCancellationRecordEntity::incrementRetryCount);
            return 0;
        });

        newBatch().run();

        assertThat(store.handedOutIds())
                .as("全レコードが取りこぼしなく処理されること（OFFSET ページングへ戻すと読み飛ばす）")
                .containsExactlyInAnyOrderElementsOf(
                        all.stream().map(RecruitmentCancellationRecordEntity::getId).collect(Collectors.toList()));
        assertThat(store.handedOutIds())
                .as("同じレコードを二度掴まない（1 レコードあたりの徴収は高々 1 回）")
                .doesNotHaveDuplicates();
    }

    // ==========================================================
    // ヘルパー
    // ==========================================================

    private void givenChunks(List<RecruitmentCancellationRecordEntity> records) {
        FakeStore store = new FakeStore(records);
        given(cancellationRecordRepository.findFailedForRetryAfterId(anyInt(), anyLong(), any(Pageable.class)))
                .willAnswer(invocation -> store.findByCursor(
                        invocation.getArgument(0), invocation.getArgument(1),
                        ((Pageable) invocation.getArgument(2)).getPageSize()));
    }

    /** 宣言済みコンストラクタを型で解決して組み立てる（依存が増えてもテストを書き換えずに済む）。 */
    private RecruitmentPaymentRetryBatch newBatch() {
        Constructor<?> ctor = RecruitmentPaymentRetryBatch.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterTypes().length];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = ctor.getParameterTypes()[i];
            if (type.isAssignableFrom(RecruitmentCancellationRecordRepository.class)) {
                args[i] = cancellationRecordRepository;
            } else if (type.isAssignableFrom(RecruitmentCancellationFeeRetryProcessor.class)) {
                args[i] = retryProcessor;
            } else if (type.isAssignableFrom(ConnectChargeService.class)) {
                args[i] = connectChargeService;
            } else {
                throw new IllegalStateException("未知の依存: " + type.getName());
            }
        }
        try {
            ctor.setAccessible(true);
            return (RecruitmentPaymentRetryBatch) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private RecruitmentCancellationRecordEntity failedRecord(Long id, int retryCount) {
        RecruitmentCancellationRecordEntity r = RecruitmentCancellationRecordEntity.builder()
                .participantId(200L + id)
                .listingId(100L)
                .userId(id)
                .teamId(10L)
                .cancelledAt(LocalDateTime.now())
                .cancelledBy(id)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(6)
                .feeAmount(3_000)
                .paymentStatus(CancellationPaymentStatus.FAILED)
                .build();
        setField(r, "id", id);
        setField(r, "paymentRetryCount", retryCount);
        return r;
    }

    /** {@code id > cursor AND paymentRetryCount < maxRetries} という実 DB と同じ意味論のインメモリ Fake。 */
    private static final class FakeStore {
        private final List<RecruitmentCancellationRecordEntity> records;
        private final List<Long> handedOutIds = new ArrayList<>();

        FakeStore(List<RecruitmentCancellationRecordEntity> records) {
            this.records = new ArrayList<>(records);
        }

        List<RecruitmentCancellationRecordEntity> findByCursor(int maxRetries, long cursor, int pageSize) {
            List<RecruitmentCancellationRecordEntity> page = records.stream()
                    .filter(r -> r.getId() > cursor)
                    .filter(r -> r.getPaymentRetryCount() < maxRetries)
                    .sorted(Comparator.comparing(RecruitmentCancellationRecordEntity::getId))
                    .limit(pageSize)
                    .collect(Collectors.toList());
            page.forEach(r -> handedOutIds.add(r.getId()));
            return page;
        }

        List<Long> handedOutIds() {
            return handedOutIds;
        }
    }

    private static void setField(Object entity, String name, Object value) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("フィールドが見つからない: " + name);
    }
}
