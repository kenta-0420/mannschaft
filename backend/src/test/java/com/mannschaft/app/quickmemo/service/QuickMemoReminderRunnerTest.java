package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.event.QuickMemoReminderNotificationEvent;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link QuickMemoReminderRunner} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>是正前は {@code QuickMemoReminderBatchService} の中で「通知 → 送信済み記録」を
 * バッチ全体の 1 トランザクションで行っていた。本テストは 1 ユーザー単位で完結すること、
 * <b>抽出時点のスナップショットを信じず読み直して再判定する</b>こと（冪等）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickMemoReminderRunner 単体テスト")
class QuickMemoReminderRunnerTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Mock private QuickMemoRepository memoRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private QuickMemoReminderRunner runner;

    /**
     * リマインド枠の状態を指定してメモを 1 件作る。
     *
     * @param sched1 reminder1 の予定時刻（{@code null} 可）
     * @param sent1  reminder1 の送信済み時刻（{@code null} なら未送信）
     * @param sched2 reminder2 の予定時刻（{@code null} 可）
     */
    private QuickMemoEntity memo(Long id, Long userId,
                                 LocalDateTime sched1, LocalDateTime sent1, LocalDateTime sched2) {
        QuickMemoEntity m = QuickMemoEntity.builder()
                .userId(userId)
                .title("m" + id)
                .reminder1ScheduledAt(sched1)
                .reminder1SentAt(sent1)
                .reminder2ScheduledAt(sched2)
                .build();
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    @Test
    @DisplayName("reminder1 が期限到来かつ未送信なら記録し、配送要求を publish する")
    void reminder1を記録してpublishする() {
        LocalDateTime now = LocalDateTime.now(JST);
        QuickMemoEntity m = memo(1L, 100L, now.minusMinutes(10), null, null);
        given(memoRepository.findAllById(List.of(1L))).willReturn(List.of(m));

        assertThat(runner.markRemindersSent(100L, List.of(1L), now)).isEqualTo(1);

        verify(memoRepository).markReminder1Sent(1L, now);
        verify(memoRepository, never()).markReminder2Sent(any(), any());
        verify(memoRepository, never()).markReminder3Sent(any(), any());

        ArgumentCaptor<QuickMemoReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(QuickMemoReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recipientUserId()).isEqualTo(100L);
        assertThat(captor.getValue().memoCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reminder1 が送信済みなら reminder2 だけ記録する")
    void reminder2のみ記録する() {
        LocalDateTime now = LocalDateTime.now(JST);
        QuickMemoEntity m = memo(1L, 100L, now.minusHours(2), now.minusHours(1), now.minusMinutes(5));
        given(memoRepository.findAllById(List.of(1L))).willReturn(List.of(m));

        assertThat(runner.markRemindersSent(100L, List.of(1L), now)).isEqualTo(1);

        verify(memoRepository, never()).markReminder1Sent(any(), any());
        verify(memoRepository).markReminder2Sent(1L, now);
    }

    @Test
    @DisplayName("AC-4: 読み直した結果すべて送信済みなら記録も publish もしない（冪等）")
    void 全て送信済みなら何もしない() {
        LocalDateTime now = LocalDateTime.now(JST);
        QuickMemoEntity m = memo(1L, 100L, now.minusHours(2), now.minusHours(1), null);
        given(memoRepository.findAllById(List.of(1L))).willReturn(List.of(m));

        assertThat(runner.markRemindersSent(100L, List.of(1L), now)).isZero();

        verify(memoRepository, never()).markReminder1Sent(any(), any());
        verify(eventPublisher, never()).publishEvent(any(QuickMemoReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("AC-4: 抽出後に削除されていたメモは対象から外れる")
    void 削除済みメモは対象外() {
        LocalDateTime now = LocalDateTime.now(JST);
        given(memoRepository.findAllById(List.of(1L))).willReturn(List.of());

        assertThat(runner.markRemindersSent(100L, List.of(1L), now)).isZero();

        verify(eventPublisher, never()).publishEvent(any(QuickMemoReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("所有者が一致しないメモは触らない")
    void 他人のメモは触らない() {
        LocalDateTime now = LocalDateTime.now(JST);
        QuickMemoEntity m = memo(1L, 999L, now.minusMinutes(10), null, null);
        given(memoRepository.findAllById(List.of(1L))).willReturn(List.of(m));

        assertThat(runner.markRemindersSent(100L, List.of(1L), now)).isZero();

        verify(memoRepository, never()).markReminder1Sent(any(), any());
        verify(eventPublisher, never()).publishEvent(any(QuickMemoReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("複数メモは 1 件の集約通知にまとめられる")
    void 複数メモは集約される() {
        LocalDateTime now = LocalDateTime.now(JST);
        QuickMemoEntity m1 = memo(1L, 100L, now.minusMinutes(10), null, null);
        QuickMemoEntity m2 = memo(2L, 100L, now.minusMinutes(20), null, null);
        given(memoRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(m1, m2));

        assertThat(runner.markRemindersSent(100L, List.of(1L, 2L), now)).isEqualTo(2);

        ArgumentCaptor<QuickMemoReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(QuickMemoReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().memoCount()).isEqualTo(2);
    }
}
