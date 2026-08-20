package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoReminderBatchService} 単体テスト（F02.5 Phase 6-2）。
 *
 * <p>後方互換の {@code executeAt(LocalTime)} / {@code executeAt(LocalTime, LocalDate)} および
 * ユーザーTZ対応の {@code executeAt(ZonedDateTime)} を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoReminderBatchService 単体テスト")
class ActionMemoReminderBatchServiceTest {

    @Mock
    private UserActionMemoSettingsRepository settingsRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AuditLogService auditLogService;

    /** Issue #2715 CMP-055 lot C-5/C-6: newly added i18n dependencies. */
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private ActionMemoReminderBatchService service;

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

    // ================================================================
    // 後方互換テスト: executeAt(LocalTime) / executeAt(LocalTime, LocalDate)
    // ================================================================

    @Nested
    @DisplayName("後方互換: executeAt(LocalTime)")
    class LegacyExecuteAt {

        @Test
        @DisplayName("execute_対象なし_何もしない")
        void execute_対象なし_何もしない() {
            // given
            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of());

            // when
            service.executeAt(LocalTime.of(9, 0));

            // then
            verify(notificationService, never()).createNotification(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(auditLogService, never()).record(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("execute_時刻一致_通知が送られる")
        void execute_時刻一致_通知が送られる() {
            // given
            LocalTime targetTime = LocalTime.of(9, 0);
            LocalDate today = LocalDate.of(2026, 5, 4);
            UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                    .userId(1L)
                    .reminderEnabled(true)
                    .reminderTime(targetTime)
                    .build();

            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of(settings));

            // when
            service.executeAt(targetTime, today);

            // then: actionUrl が /action-memo?date=YYYY-MM-DD 形式になっていることを検証
            verify(notificationService, times(1)).createNotification(
                    eq(1L),
                    eq("ACTION_MEMO_REMINDER"),
                    eq(NotificationPriority.NORMAL),
                    eq("行動メモのリマインド"),
                    eq("今日の行動メモを記録しましょう"),
                    eq("ACTION_MEMO"),
                    eq(null),
                    eq(NotificationScopeType.PERSONAL),
                    eq(1L),
                    contains("/action-memo?date="),
                    eq(null)
            );
            verify(auditLogService, times(1)).record(
                    "ACTION_MEMO_REMINDER_BATCH", null, null, null, null, null, null, null,
                    "{\"targets\":1,\"notified\":1}");
        }

        @Test
        @DisplayName("execute_locale一括解決が例外を投げても既定localeで通知処理が継続する")
        void execute_locale一括解決が例外を投げても既定localeで通知処理が継続する() {
            // Issue #2715 CMP-055 ロットC-6 Codex 検分是正（PR #2873）: getLocales がループの外・
            // 無防備な状態で呼ばれていると、この一括解決だけで全受信者分の通知処理が丸ごと止まっていた。
            // given
            LocalTime targetTime = LocalTime.of(9, 0);
            LocalDate today = LocalDate.of(2026, 5, 4);
            UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                    .userId(1L)
                    .reminderEnabled(true)
                    .reminderTime(targetTime)
                    .build();

            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of(settings));
            given(userLocaleCache.getLocales(any()))
                    .willThrow(new RuntimeException("simulated locale cache failure"));

            // when
            service.executeAt(targetTime, today);

            // then: locale 一括解決が失敗しても既定 locale ("ja") で通知処理は継続する
            verify(notificationService, times(1)).createNotification(
                    eq(1L),
                    eq("ACTION_MEMO_REMINDER"),
                    eq(NotificationPriority.NORMAL),
                    eq("行動メモのリマインド"),
                    eq("今日の行動メモを記録しましょう"),
                    eq("ACTION_MEMO"),
                    eq(null),
                    eq(NotificationScopeType.PERSONAL),
                    eq(1L),
                    contains("/action-memo?date="),
                    eq(null)
            );
            verify(auditLogService, times(1)).record(
                    "ACTION_MEMO_REMINDER_BATCH", null, null, null, null, null, null, null,
                    "{\"targets\":1,\"notified\":1}");
        }

        @Test
        @DisplayName("execute_時刻不一致_通知が送られない")
        void execute_時刻不一致_通知が送られない() {
            // given
            LocalTime reminderTime = LocalTime.of(9, 0);
            LocalTime nowTime = LocalTime.of(10, 0);

            UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                    .userId(2L)
                    .reminderEnabled(true)
                    .reminderTime(reminderTime)
                    .build();

            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of(settings));

            // when
            service.executeAt(nowTime);

            // then
            verify(notificationService, never()).createNotification(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(auditLogService, never()).record(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ================================================================
    // ユーザーTZ対応テスト: executeAt(ZonedDateTime)
    // ================================================================

    @Nested
    @DisplayName("ユーザーTZ対応: executeAt(ZonedDateTime)")
    class TimezoneAwareExecuteAt {

        /** UTC 00:00 = JST 09:00 */
        private static final ZonedDateTime NOW_UTC_MIDNIGHT =
                ZonedDateTime.of(2026, 6, 4, 0, 0, 0, 0, ZoneId.of("UTC"));

        @Test
        @DisplayName("JSTユーザー_UTC00:00はJST09:00_reminder_time=09:00_通知が送られる")
        void jstUser_UTC0000_isJST0900_reminderAt0900_送信される() {
            // given: JST ユーザー、reminder_time = 09:00
            UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                    .userId(10L)
                    .reminderEnabled(true)
                    .reminderTime(LocalTime.of(9, 0))
                    .build();

            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of(settings));
            given(userRepository.findTimezoneById(10L))
                    .willReturn(Optional.of("Asia/Tokyo"));

            // when
            service.executeAt(NOW_UTC_MIDNIGHT);

            // then
            verify(notificationService, times(1)).createNotification(
                    eq(10L), eq("ACTION_MEMO_REMINDER"), any(), any(), any(), any(), any(), any(),
                    eq(10L), contains("/action-memo?date=2026-06-04"), eq(null));
            verify(auditLogService, times(1)).record(
                    eq("ACTION_MEMO_REMINDER_BATCH"), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("UTCユーザー_UTC00:00はUTC00:00_reminder_time=09:00_時刻不一致_送信されない")
        void utcUser_UTC0000_reminderAt0900_不一致_送信されない() {
            // given: UTC ユーザー、reminder_time = 09:00（UTC では 00:00 と一致しない）
            UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                    .userId(20L)
                    .reminderEnabled(true)
                    .reminderTime(LocalTime.of(9, 0))
                    .build();

            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of(settings));
            given(userRepository.findTimezoneById(20L))
                    .willReturn(Optional.of("UTC"));

            // when
            service.executeAt(NOW_UTC_MIDNIGHT);

            // then: UTC ユーザーの 09:00 != UTC 00:00 なので送信されない
            verify(notificationService, never()).createNotification(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("UTCユーザー_UTC09:00_reminder_time=09:00_送信される")
        void utcUser_UTC0900_reminderAt0900_送信される() {
            // given: UTC ユーザー、reminder_time = 09:00、UTC 09:00
            ZonedDateTime nowUtc9 = ZonedDateTime.of(2026, 6, 4, 9, 0, 0, 0, ZoneId.of("UTC"));
            UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                    .userId(20L)
                    .reminderEnabled(true)
                    .reminderTime(LocalTime.of(9, 0))
                    .build();

            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of(settings));
            given(userRepository.findTimezoneById(20L))
                    .willReturn(Optional.of("UTC"));

            // when
            service.executeAt(nowUtc9);

            // then
            verify(notificationService, times(1)).createNotification(
                    eq(20L), eq("ACTION_MEMO_REMINDER"), any(), any(), any(), any(), any(), any(),
                    eq(20L), contains("/action-memo?date=2026-06-04"), eq(null));
        }

        @Test
        @DisplayName("ユーザーTZ未設定_フォールバックJST_UTC00:00はJST09:00_reminder_time=09:00_送信される")
        void userTzNotFound_fallbackJst_UTC0000_reminderAt0900_送信される() {
            // given: TZ 未設定ユーザー（empty）→ フォールバック Asia/Tokyo
            UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                    .userId(30L)
                    .reminderEnabled(true)
                    .reminderTime(LocalTime.of(9, 0))
                    .build();

            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of(settings));
            given(userRepository.findTimezoneById(30L))
                    .willReturn(Optional.empty());

            // when
            service.executeAt(NOW_UTC_MIDNIGHT);

            // then: フォールバック JST で 09:00 → 送信される
            verify(notificationService, times(1)).createNotification(
                    eq(30L), eq("ACTION_MEMO_REMINDER"), any(), any(), any(), any(), any(), any(),
                    eq(30L), contains("/action-memo?date="), eq(null));
        }

        @Test
        @DisplayName("ユーザーTZ不正値_フォールバックJST_UTC00:00はJST09:00_reminder_time=09:00_送信される")
        void invalidUserTz_fallbackJst_UTC0000_reminderAt0900_送信される() {
            // given: 不正 TZ 文字列 → フォールバック Asia/Tokyo
            UserActionMemoSettingsEntity settings = UserActionMemoSettingsEntity.builder()
                    .userId(40L)
                    .reminderEnabled(true)
                    .reminderTime(LocalTime.of(9, 0))
                    .build();

            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of(settings));
            given(userRepository.findTimezoneById(40L))
                    .willReturn(Optional.of("INVALID_TZ_XXXXX"));

            // when
            service.executeAt(NOW_UTC_MIDNIGHT);

            // then: フォールバック JST で 09:00 → 送信される
            verify(notificationService, times(1)).createNotification(
                    eq(40L), eq("ACTION_MEMO_REMINDER"), any(), any(), any(), any(), any(), any(),
                    eq(40L), contains("/action-memo?date="), eq(null));
        }

        @Test
        @DisplayName("対象なし_何もしない_auditLog不呼出し")
        void 対象なし_何もしない() {
            // given
            given(settingsRepository.findByReminderEnabledTrueAndReminderTimeIsNotNull())
                    .willReturn(List.of());

            // when
            service.executeAt(NOW_UTC_MIDNIGHT);

            // then
            verify(notificationService, never()).createNotification(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(auditLogService, never()).record(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
