package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link QuickMemoReminderBatchService} 単体テスト。
 *
 * <p>{@code reminder_xScheduledAt} は JST LocalDateTime として保存されており、
 * バッチの {@code now} も JST で取得することを確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickMemoReminderBatchService 単体テスト")
class QuickMemoReminderBatchServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Mock
    private QuickMemoRepository memoRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AuditLogService auditLogService;

    /** Issue #2715 CMP-055 lot C-5/C-6: newly added i18n dependencies. */
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private QuickMemoReminderBatchService service;

    /**
     * Issue #2715 CMP-055 lot C-5/C-6: the bare MessageSource mock would return null for
     * title/body. Return the supplied default message so existing assertions keep working.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubI18nMessageSource() {
        org.mockito.Mockito.lenient().when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    @Nested
    @DisplayName("execute: リマインド対象なし")
    class 対象なし {

        @Test
        @DisplayName("対象メモなし_何もしない")
        void 対象なし_何もしない() {
            // given
            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            service.execute();

            // then
            verify(notificationService, never()).createNotification(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
            // given
            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of());

            // when
            service.execute();

            // then: findReminderTargets に渡された now を取得する
            ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(memoRepository).findReminderTargets(nowCaptor.capture(), any(Pageable.class));

            LocalDateTime capturedNow = nowCaptor.getValue();
            // JST 基準の now と UTC 基準の now を比較して 9 時間のずれを確認する
            // （ただしテスト実行のわずかな時間差を考慮し、差分が ±1 分以内でない＝9時間ずれる UTC 基準でないことを検証）
            LocalDateTime nowJst = LocalDateTime.now(JST);
            LocalDateTime nowUtc = LocalDateTime.now(ZoneId.of("UTC"));

            // キャプチャされた値が JST 基準に近いことを確認（UTC との差が 5 時間以上なら UTCではない）
            long diffFromJstMinutes = Math.abs(
                    java.time.Duration.between(capturedNow, nowJst).toMinutes());
            long diffFromUtcMinutes = Math.abs(
                    java.time.Duration.between(capturedNow, nowUtc).toMinutes());

            // JST との差は 0〜1分（テスト実行時間）、UTC との差は約 540 分（9時間）
            assertThat(diffFromJstMinutes).isLessThan(2);
            assertThat(diffFromUtcMinutes).isGreaterThan(300); // UTC との差が 5 時間以上
        }
    }

    @Nested
    @DisplayName("execute: リマインド対象あり")
    class 対象あり {

        @Test
        @DisplayName("reminder1が期限到来_通知送信とreminder1SentAt記録")
        void reminder1期限到来_通知送信と記録() {
            // given: reminder1ScheduledAt が現在時刻（JST）より前、reminder1SentAt は未送信
            LocalDateTime pastJst = LocalDateTime.now(JST).minusMinutes(10);
            QuickMemoEntity memo = QuickMemoEntity.builder()
                    .userId(1L)
                    .title("テストメモ")
                    .reminder1ScheduledAt(pastJst)
                    .build();

            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(memo));

            // when
            service.execute();

            // then
            verify(notificationService, times(1)).createNotification(
                    eq(1L), eq("QUICK_MEMO_REMINDER"), any(), any(), any(), any(), any(), any(),
                    eq(1L), any(), any());
            verify(memoRepository, times(1)).markReminder1Sent(eq(memo.getId()), any(LocalDateTime.class));
            verify(memoRepository, never()).markReminder2Sent(any(), any());
            verify(memoRepository, never()).markReminder3Sent(any(), any());
            verify(auditLogService, times(1)).record(
                    eq("QUICK_MEMO_REMINDER_BATCH"), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("reminder1送信済み_reminder2未送信_reminder2だけ記録")
        void reminder2のみ送信() {
            // given
            LocalDateTime pastJst = LocalDateTime.now(JST).minusMinutes(10);
            LocalDateTime reminder1SentAt = pastJst.minusMinutes(30);
            QuickMemoEntity memo = QuickMemoEntity.builder()
                    .userId(2L)
                    .title("テストメモ2")
                    .reminder1ScheduledAt(pastJst.minusHours(2))
                    .reminder1SentAt(reminder1SentAt)
                    .reminder2ScheduledAt(pastJst)
                    .build();

            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(memo));

            // when
            service.execute();

            // then: reminder1 は送信済みなのでスキップ、reminder2 だけ記録
            verify(memoRepository, never()).markReminder1Sent(any(), any());
            verify(memoRepository, times(1)).markReminder2Sent(eq(memo.getId()), any(LocalDateTime.class));
            verify(memoRepository, never()).markReminder3Sent(any(), any());
        }

        @Test
        @DisplayName("複数ユーザー_ユーザー単位集約で通知")
        void 複数ユーザー_ユーザー単位集約() {
            // given: userId=1 のメモが2件、userId=2 のメモが1件
            LocalDateTime pastJst = LocalDateTime.now(JST).minusMinutes(5);
            QuickMemoEntity memo1a = QuickMemoEntity.builder().userId(1L).title("m1a")
                    .reminder1ScheduledAt(pastJst).build();
            QuickMemoEntity memo1b = QuickMemoEntity.builder().userId(1L).title("m1b")
                    .reminder1ScheduledAt(pastJst).build();
            QuickMemoEntity memo2 = QuickMemoEntity.builder().userId(2L).title("m2")
                    .reminder1ScheduledAt(pastJst).build();

            given(memoRepository.findReminderTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(memo1a, memo1b, memo2));

            // when
            service.execute();

            // then: 通知は userId=1 に 1 回、userId=2 に 1 回 = 計 2 回
            verify(notificationService, times(2)).createNotification(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
