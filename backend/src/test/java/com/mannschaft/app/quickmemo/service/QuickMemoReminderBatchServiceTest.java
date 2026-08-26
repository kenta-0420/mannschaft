package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link QuickMemoReminderBatchService} 単体テスト。
 *
 * <p>{@code reminder_xScheduledAt} は JST LocalDateTime として保存されており、
 * バッチの {@code now} も JST で取得することを確認する。</p>
 *
 * <p>Issue #2834 / CMP-056 第2群ロット1 でバッチが<b>非トランザクションのオーケストレータ</b>に
 * なったため、本テストの関心は「対象抽出 → ユーザーごとに {@link QuickMemoReminderRunner} を呼ぶ →
 * 失敗しても次へ → 監査ログを残す」に絞る。リマインド枠の記録と通知の中身は
 * {@code QuickMemoReminderRunnerTest} /
 * {@code com.mannschaft.app.quickmemo.event.QuickMemoReminderNotificationListenerTest} が担当する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickMemoReminderBatchService 単体テスト")
class QuickMemoReminderBatchServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Mock private QuickMemoRepository memoRepository;
    @Mock private QuickMemoReminderRunner quickMemoReminderRunner;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private QuickMemoReminderBatchService service;

    private QuickMemoEntity memo(Long id, Long userId) {
        QuickMemoEntity m = QuickMemoEntity.builder()
                .userId(userId)
                .title("m" + id)
                .reminder1ScheduledAt(LocalDateTime.now(JST).minusMinutes(10))
                .build();
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    @Nested
    @DisplayName("execute: リマインド対象なし")
    class 対象なし {

        @Test
        @DisplayName("対象メモなし_何もしない")
        void 対象なし_何もしない() {
            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of());

            service.execute();

            verify(quickMemoReminderRunner, never()).markRemindersSent(any(), any(), any());
            verify(auditLogService, never()).record(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("execute: リポジトリに渡す now が JST 基準であること")
    class NowIsJst {

        @Test
        @DisplayName("findReminderTargetsに渡すnowがJST基準のLocalDateTimeである")
        void findReminderTargets_nowがJST基準() {
            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of());

            service.execute();

            ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(memoRepository).findReminderTargets(nowCaptor.capture(), any(Pageable.class));

            LocalDateTime capturedNow = nowCaptor.getValue();
            LocalDateTime nowJst = LocalDateTime.now(JST);
            LocalDateTime nowUtc = LocalDateTime.now(ZoneId.of("UTC"));

            long diffFromJstMinutes = Math.abs(
                    java.time.Duration.between(capturedNow, nowJst).toMinutes());
            long diffFromUtcMinutes = Math.abs(
                    java.time.Duration.between(capturedNow, nowUtc).toMinutes());

            assertThat(diffFromJstMinutes).isLessThan(2);
            assertThat(diffFromUtcMinutes).isGreaterThan(300); // UTC との差が 5 時間以上
        }
    }

    @Nested
    @DisplayName("execute: リマインド対象あり")
    class 対象あり {

        @Test
        @DisplayName("ユーザー単位に集約して Runner が呼ばれる")
        void ユーザー単位集約() {
            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(memo(1L, 100L), memo(2L, 100L), memo(3L, 200L)));
            given(quickMemoReminderRunner.markRemindersSent(anyLong(), anyList(), any())).willReturn(1);

            service.execute();

            verify(quickMemoReminderRunner).markRemindersSent(eq(100L), eq(List.of(1L, 2L)), any());
            verify(quickMemoReminderRunner).markRemindersSent(eq(200L), eq(List.of(3L)), any());
            verify(auditLogService, times(1)).record(
                    eq("QUICK_MEMO_REMINDER_BATCH"), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC-1: 1ユーザーが例外でも後続ユーザーは処理され、監査ログも残る")
        void 一ユーザー失敗でも後続は処理される() {
            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(memo(1L, 100L), memo(2L, 200L)));
            willThrow(new RuntimeException("模擬DB例外"))
                    .given(quickMemoReminderRunner).markRemindersSent(eq(100L), anyList(), any());
            given(quickMemoReminderRunner.markRemindersSent(eq(200L), anyList(), any())).willReturn(1);

            assertThatCode(() -> service.execute()).doesNotThrowAnyException();

            verify(quickMemoReminderRunner).markRemindersSent(eq(200L), anyList(), any());
            // 監査ログはオーケストレータ側（TX 外）で記録するため、失敗があっても消えない。
            verify(auditLogService, times(1)).record(
                    eq("QUICK_MEMO_REMINDER_BATCH"), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
