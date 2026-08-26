package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ShiftCleanupBatchService} のユニットテスト。F03.5 Phase 4-α。
 */
@ExtendWith(MockitoExtension.class)
class ShiftCleanupBatchServiceTest {

    @Mock private ShiftSwapRequestRepository swapRepository;
    @Mock private ShiftScheduleRepository scheduleRepository;
    @Mock private ShiftRequestRepository requestRepository;
    @Mock private NotificationHelper notificationHelper;
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private ShiftCleanupBatchService batchService;

    // Issue #2715 ロットB: 依存追加に伴う mock 漏れ対策。
    // getLocale が null を返すと Locale.forLanguageTag(null) が NPE になり、
    // それが notifySwapExpired の try/catch に飲まれて「通知されない」という無関係な失敗に化ける。
    @BeforeEach
    void setUpLocaleStubs() {
        org.mockito.Mockito.lenient().when(userLocaleCache.getLocale(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("ja");
        org.mockito.Mockito.lenient().when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    // =========================================================
    // runSwapExpiryCancel
    // =========================================================

    @Nested
    @DisplayName("runSwapExpiryCancel")
    class RunSwapExpiryCancel {

        @Test
        @DisplayName("期限切れ PENDING スワップが CANCELLED に遷移し save される")
        void 期限切れスワップをキャンセル() {
            ShiftSwapRequestEntity swap = buildPendingSwap(1L, 10L, null);
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swap));

            batchService.runSwapExpiryCancel();

            assertThat(swap.getStatus()).isEqualTo(SwapRequestStatus.CANCELLED);
            verify(swapRepository).save(swap);
        }

        @Test
        @DisplayName("Issue #2715 ロットB: 受信者 locale が en の場合、通知件名・本文が英語で組み立てられプレースホルダが残らない")
        void 受信者ロケールがenなら英語件名本文になる() {
            var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
            realMessageSource.setBasename("messages");
            realMessageSource.setDefaultEncoding("UTF-8");
            org.springframework.test.util.ReflectionTestUtils.setField(
                    batchService, "messageSource", realMessageSource);
            given(userLocaleCache.getLocale(10L)).willReturn("en");

            ShiftSwapRequestEntity swap = buildPendingSwap(9L, 10L, null);
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swap));

            batchService.runSwapExpiryCancel();

            org.mockito.ArgumentCaptor<String> titleCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(notificationHelper).notify(
                    eq(10L), eq("SHIFT_SWAP_EXPIRED"),
                    titleCaptor.capture(), bodyCaptor.capture(),
                    eq("SHIFT_SWAP_REQUEST"), eq(9L),
                    any(), eq(10L), anyString(), isNull());
            assertThat(titleCaptor.getValue()).doesNotContain("{0}").isEqualTo("Shift swap request has expired");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}");
        }

        @Test
        @DisplayName("requester に通知が送信される")
        void requesterに通知が届く() {
            ShiftSwapRequestEntity swap = buildPendingSwap(1L, 10L, null);
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swap));

            batchService.runSwapExpiryCancel();

            verify(notificationHelper).notify(
                    eq(10L), eq("SHIFT_SWAP_EXPIRED"),
                    anyString(), anyString(),
                    eq("SHIFT_SWAP_REQUEST"), eq(1L),
                    any(), eq(10L),
                    anyString(), isNull());
        }

        @Test
        @DisplayName("targetUserId != null の場合 requester と target の両方に通知が届く")
        void targetあり両方に通知() {
            ShiftSwapRequestEntity swap = buildPendingSwap(2L, 10L, 20L);
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swap));

            batchService.runSwapExpiryCancel();

            verify(notificationHelper).notify(
                    eq(10L), eq("SHIFT_SWAP_EXPIRED"),
                    anyString(), anyString(),
                    eq("SHIFT_SWAP_REQUEST"), eq(2L),
                    any(), eq(10L), anyString(), isNull());
            verify(notificationHelper).notify(
                    eq(20L), eq("SHIFT_SWAP_EXPIRED"),
                    anyString(), anyString(),
                    eq("SHIFT_SWAP_REQUEST"), eq(2L),
                    any(), eq(20L), anyString(), isNull());
        }

        @Test
        @DisplayName("targetUserId == null の場合 requester のみに通知が届く")
        void targetなしrequesterのみに通知() {
            ShiftSwapRequestEntity swap = buildPendingSwap(3L, 10L, null);
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swap));

            batchService.runSwapExpiryCancel();

            verify(notificationHelper).notify(
                    eq(10L), eq("SHIFT_SWAP_EXPIRED"),
                    anyString(), anyString(),
                    eq("SHIFT_SWAP_REQUEST"), eq(3L),
                    any(), eq(10L), anyString(), isNull());
        }

        @Test
        @DisplayName("対象が 0 件の場合は何もしない")
        void 対象なしは処理なし() {
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of());

            batchService.runSwapExpiryCancel();

            verify(swapRepository, never()).save(any());
            verify(notificationHelper, never()).notify(
                    anyLong(), anyString(), anyString(), anyString(),
                    anyString(), anyLong(), any(), anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("楽観ロック競合時はスキップして他の処理を継続する")
        void 楽観ロック競合時はスキップ() {
            ShiftSwapRequestEntity swap1 = buildPendingSwap(1L, 10L, null);
            ShiftSwapRequestEntity swap2 = buildPendingSwap(2L, 20L, null);
            given(swapRepository.findExpiredPendingBefore(any(), any(Pageable.class)))
                    .willReturn(List.of(swap1, swap2));
            given(swapRepository.save(swap1))
                    .willThrow(new ObjectOptimisticLockingFailureException(ShiftSwapRequestEntity.class, 1L));

            batchService.runSwapExpiryCancel();

            verify(swapRepository).save(swap2);
        }
    }

    // =========================================================
    // runRequestCleanup
    // =========================================================

    @Nested
    @DisplayName("runRequestCleanup")
    class RunRequestCleanup {

        @Test
        @DisplayName("ARCHIVED 30 日超過スケジュールの希望が物理削除される")
        void アーカイブ済み希望が物理削除される() {
            given(scheduleRepository.findArchivedScheduleIdsOlderThan(any(), any(Pageable.class)))
                    .willReturn(List.of(1L, 2L));
            given(requestRepository.deleteByScheduleIds(anyList())).willReturn(50);

            batchService.runRequestCleanup();

            verify(requestRepository).deleteByScheduleIds(List.of(1L, 2L));
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
            List<Long> ids = List.of(10L, 20L, 30L);
            given(scheduleRepository.findArchivedScheduleIdsOlderThan(any(), any(Pageable.class)))
                    .willReturn(ids);
            given(requestRepository.deleteByScheduleIds(ids)).willReturn(150);

            batchService.runRequestCleanup();

            verify(requestRepository).deleteByScheduleIds(ids);
        }

        @Test
        @DisplayName("findArchivedScheduleIdsOlderThan に pageable が渡される")
        void pageableが渡される() {
            given(scheduleRepository.findArchivedScheduleIdsOlderThan(any(), any(Pageable.class)))
                    .willReturn(List.of());

            batchService.runRequestCleanup();

            verify(scheduleRepository).findArchivedScheduleIdsOlderThan(any(), any(Pageable.class));
        }
    }

    // =========================================================
    // ヘルパー
    // =========================================================

    private ShiftSwapRequestEntity buildPendingSwap(Long id, Long requesterId, Long targetUserId) {
        ShiftSwapRequestEntity entity = ShiftSwapRequestEntity.builder()
                .slotId(100L)
                .requesterId(requesterId)
                .targetUserId(targetUserId)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "status", SwapRequestStatus.PENDING);
        return entity;
    }
}
