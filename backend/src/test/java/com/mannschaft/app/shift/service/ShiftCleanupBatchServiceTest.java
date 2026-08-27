package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftCleanupBatchService} のユニットテスト。F03.5 Phase 4-α。
 *
 * <p>Issue #2834 / CMP-056 第2群ロット1 でバッチが<b>非トランザクションのオーケストレータ</b>になったため、
 * 本テストの関心は「対象抽出 → 項目ごとに {@link ShiftSwapExpiryRunner} を呼ぶ → 失敗しても次へ」に絞る。
 * キャンセル自体と通知の中身は {@code ShiftSwapExpiryRunnerTest} /
 * {@code ShiftSwapExpiredNotificationListenerTest} が担当する。</p>
 */
@ExtendWith(MockitoExtension.class)
class ShiftCleanupBatchServiceTest {

    @Mock private ShiftSwapRequestRepository swapRepository;
    @Mock private ShiftScheduleRepository scheduleRepository;
    @Mock private ShiftRequestRepository requestRepository;
    @Mock private ShiftSwapExpiryRunner shiftSwapExpiryRunner;

    @InjectMocks
    private ShiftCleanupBatchService batchService;

    private ShiftSwapRequestEntity swapWithId(Long id) {
        ShiftSwapRequestEntity swap = ShiftSwapRequestEntity.builder()
                .requesterId(100L)
                .build();
        ReflectionTestUtils.setField(swap, "id", id);
        return swap;
    }

    // =========================================================
    // runSwapExpiryCancel
    // =========================================================

    @Nested
    @DisplayName("runSwapExpiryCancel")
    class RunSwapExpiryCancel {

        @Test
        @DisplayName("抽出した申請ごとに Runner が独立トランザクションで呼ばれる")
        void 申請ごとにRunnerが呼ばれる() {
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swapWithId(1L), swapWithId(2L)));
            given(shiftSwapExpiryRunner.cancelOne(anyLong())).willReturn(true);

            batchService.runSwapExpiryCancel();

            verify(shiftSwapExpiryRunner).cancelOne(1L);
            verify(shiftSwapExpiryRunner).cancelOne(2L);
        }

        @Test
        @DisplayName("AC-1: 1件が例外でも後続の申請は処理される（バッチ全体を巻き戻さない）")
        void 一件失敗しても後続は処理される() {
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swapWithId(1L), swapWithId(2L), swapWithId(3L)));
            willThrow(new RuntimeException("模擬DB例外"))
                    .given(shiftSwapExpiryRunner).cancelOne(2L);
            given(shiftSwapExpiryRunner.cancelOne(eq(1L))).willReturn(true);
            given(shiftSwapExpiryRunner.cancelOne(eq(3L))).willReturn(true);

            assertThatCode(() -> batchService.runSwapExpiryCancel()).doesNotThrowAnyException();

            verify(shiftSwapExpiryRunner).cancelOne(1L);
            verify(shiftSwapExpiryRunner).cancelOne(3L);
        }

        @Test
        @DisplayName("楽観ロック競合時はスキップして他の処理を継続する")
        void 楽観ロック競合時はスキップ() {
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swapWithId(1L), swapWithId(2L)));
            willThrow(new ObjectOptimisticLockingFailureException(ShiftSwapRequestEntity.class, 1L))
                    .given(shiftSwapExpiryRunner).cancelOne(1L);
            given(shiftSwapExpiryRunner.cancelOne(eq(2L))).willReturn(true);

            assertThatCode(() -> batchService.runSwapExpiryCancel()).doesNotThrowAnyException();

            verify(shiftSwapExpiryRunner).cancelOne(2L);
        }

        @Test
        @DisplayName("対象が 0 件の場合は Runner を呼ばない")
        void 対象なしは処理なし() {
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of());

            batchService.runSwapExpiryCancel();

            verify(shiftSwapExpiryRunner, never()).cancelOne(any());
        }
    }

    // =========================================================
    // runRequestCleanup（Issue #2834 の是正対象外。単一のバルク DELETE のまま）
    // =========================================================

    @Nested
    @DisplayName("runRequestCleanup")
    class RunRequestCleanup {

        @Test
        @DisplayName("ARCHIVED 30 日超過スケジュールの希望が物理削除される")
        void アーカイブ済み希望が物理削除される() {
            given(scheduleRepository.findArchivedScheduleIdsOlderThan(any(), any(Pageable.class)))
                    .willReturn(List.of(10L));
            given(requestRepository.deleteByScheduleIds(List.of(10L))).willReturn(3);

            batchService.runRequestCleanup();

            verify(requestRepository, times(1)).deleteByScheduleIds(List.of(10L));
        }

        @Test
        @DisplayName("scheduleIds が空の場合は deleteByScheduleIds を呼ばない")
        void scheduleIdsが空なら削除しない() {
            given(scheduleRepository.findArchivedScheduleIdsOlderThan(any(), any(Pageable.class)))
                    .willReturn(List.of());

            batchService.runRequestCleanup();

            verify(requestRepository, never()).deleteByScheduleIds(any());
        }

        @Test
        @DisplayName("複数スケジュール ID がまとめて削除される")
        void 複数スケジュールIDを一括削除() {
            given(scheduleRepository.findArchivedScheduleIdsOlderThan(any(), any(Pageable.class)))
                    .willReturn(List.of(10L, 11L, 12L));
            given(requestRepository.deleteByScheduleIds(List.of(10L, 11L, 12L))).willReturn(9);

            batchService.runRequestCleanup();

            verify(requestRepository).deleteByScheduleIds(List.of(10L, 11L, 12L));
        }

        @Test
        @DisplayName("findArchivedScheduleIdsOlderThan に pageable が渡される")
        void pageableが渡される() {
            given(scheduleRepository.findArchivedScheduleIdsOlderThan(any(), any(Pageable.class)))
                    .willReturn(List.of());

            batchService.runRequestCleanup();

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(scheduleRepository).findArchivedScheduleIdsOlderThan(any(), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        }
    }
}
