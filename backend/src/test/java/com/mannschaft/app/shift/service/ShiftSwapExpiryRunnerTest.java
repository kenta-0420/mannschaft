package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.event.ShiftSwapExpiredNotificationEvent;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftSwapExpiryRunner} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>是正前は {@code ShiftCleanupBatchService} の中でキャンセルと通知を同一トランザクションで行っていた。
 * 本テストは「キャンセルの確定」と「通知配送要求の publish」が 1 件単位で完結し、
 * 再実行しても二重にキャンセル・二重に publish しないこと（冪等）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftSwapExpiryRunner 単体テスト")
class ShiftSwapExpiryRunnerTest {

    @Mock private ShiftSwapRequestRepository swapRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ShiftSwapExpiryRunner runner;

    private ShiftSwapRequestEntity pendingSwap(Long id, Long targetUserId) {
        ShiftSwapRequestEntity swap = ShiftSwapRequestEntity.builder()
                .requesterId(100L)
                .targetUserId(targetUserId)
                .build();
        ReflectionTestUtils.setField(swap, "id", id);
        return swap;
    }

    @Test
    @DisplayName("PENDING の申請はキャンセルされ、申請者と相手ぶんの配送要求が publish される")
    void PENDINGをキャンセルして通知要求をpublishする() {
        ShiftSwapRequestEntity swap = pendingSwap(1L, 200L);
        given(swapRepository.findById(1L)).willReturn(Optional.of(swap));

        assertThat(runner.cancelOne(1L)).isTrue();

        assertThat(swap.getStatus()).isEqualTo(SwapRequestStatus.CANCELLED);
        verify(swapRepository).save(swap);

        ArgumentCaptor<ShiftSwapExpiredNotificationEvent> captor =
                ArgumentCaptor.forClass(ShiftSwapExpiredNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recipientUserIds()).containsExactly(100L, 200L);
    }

    @Test
    @DisplayName("targetUserId が未定なら申請者だけが受信者になる")
    void 相手未定なら申請者のみ() {
        ShiftSwapRequestEntity swap = pendingSwap(1L, null);
        given(swapRepository.findById(1L)).willReturn(Optional.of(swap));

        runner.cancelOne(1L);

        ArgumentCaptor<ShiftSwapExpiredNotificationEvent> captor =
                ArgumentCaptor.forClass(ShiftSwapExpiredNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recipientUserIds()).containsExactly(100L);
    }

    @Test
    @DisplayName("AC-4: 抽出後に PENDING でなくなっていたら何もせず false を返す（冪等）")
    void PENDINGでなければ何もしない() {
        ShiftSwapRequestEntity swap = pendingSwap(1L, 200L);
        swap.cancel();
        given(swapRepository.findById(1L)).willReturn(Optional.of(swap));

        assertThat(runner.cancelOne(1L)).isFalse();

        verify(swapRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ShiftSwapExpiredNotificationEvent.class));
    }

    @Test
    @DisplayName("AC-4: 申請が既に存在しなければ何もせず false を返す")
    void 申請が存在しなければ何もしない() {
        given(swapRepository.findById(1L)).willReturn(Optional.empty());

        assertThat(runner.cancelOne(1L)).isFalse();

        verify(swapRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ShiftSwapExpiredNotificationEvent.class));
    }
}
