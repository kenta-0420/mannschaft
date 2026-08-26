package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.dto.CreateReminderRequest;
import com.mannschaft.app.schedule.dto.ReminderResponse;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ReminderNotificationEvent;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleReminderService} の単体テスト。
 * リマインダーの作成（相対/絶対）・一覧取得・即時リマインド（通知発火）・実効時刻ベースのバッチ処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleReminderService 単体テスト")
class ScheduleReminderServiceTest {

    @Mock
    private ScheduleAttendanceReminderRepository reminderRepository;

    @Mock
    private ScheduleAttendanceRepository attendanceRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private ScheduleReminderService reminderService;

    @BeforeEach
    void injectSelf() {
        // self-invocation の @Lazy 自己参照は InjectMocks では埋まらないため、
        // トランザクション境界の検証を伴わない単体テストでは自分自身を割り当てる。
        ReflectionTestUtils.setField(reminderService, "self", reminderService);
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;

    private ScheduleAttendanceReminderEntity createAbsoluteReminderEntity(LocalDateTime remindAt) {
        return ScheduleAttendanceReminderEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .reminderKind(ReminderKind.ABSOLUTE)
                .remindAt(remindAt)
                .isSent(false)
                .build();
    }

    private ScheduleAttendanceReminderEntity createRelativeReminderEntity(int minutesBefore) {
        return ScheduleAttendanceReminderEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .reminderKind(ReminderKind.RELATIVE)
                .remindBeforeMinutes(minutesBefore)
                .isSent(false)
                .build();
    }

    private ScheduleEntity createSchedule(LocalDateTime startAt, boolean attendanceRequired) {
        return createSchedule(startAt, attendanceRequired, SCHEDULE_ID);
    }

    /**
     * id を明示的に指定できる版。複数スケジュールを跨いだシナリオ（例: 1件送信失敗テストで
     * scheduleId=2 のスケジュールを解決する）では、固定 {@link #SCHEDULE_ID} を返す版では
     * {@code scheduleRepository.findById(2L)} の戻り値が id=1 のスケジュールになってしまい、
     * 後続の {@code attendanceRepository} 呼び出しが誤った scheduleId で行われてしまうため。
     */
    private ScheduleEntity createSchedule(LocalDateTime startAt, boolean attendanceRequired, Long id) {
        ScheduleEntity schedule = ScheduleEntity.builder()
                .teamId(50L)
                .title("テスト予定")
                .startAt(startAt)
                .attendanceRequired(attendanceRequired)
                .build();
        // id は BaseEntity 由来で @Builder では設定できないためリフレクションで付与
        org.springframework.test.util.ReflectionTestUtils.setField(schedule, "id", id);
        return schedule;
    }

    private void stubMessages() {
        given(messageSource.getMessage(anyString(), any(), anyString(), any()))
                .willAnswer(invocation -> invocation.getArgument(2));
    }

    // ========================================
    // createReminders
    // ========================================

    @Nested
    @DisplayName("createReminders")
    class CreateReminders {

        @Test
        @DisplayName("絶対指定（JST: +09:00）_JSTのLocalDateTimeに変換されて保存される")
        void 絶対指定_JSTオフセット_JSTLocalDateTimeで保存される() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // JST（+09:00）で2026-06-05 08:00 を指定
            OffsetDateTime remindAt = OffsetDateTime.of(2026, 6, 5, 8, 0, 0, 0, ZoneOffset.ofHours(9));
            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(remindAt, null, ReminderKind.ABSOLUTE));

            // when
            List<ReminderResponse> result = reminderService.createReminders(SCHEDULE_ID, requests);

            // then
            assertThat(result).hasSize(1);
            ArgumentCaptor<ScheduleAttendanceReminderEntity> captor =
                    ArgumentCaptor.forClass(ScheduleAttendanceReminderEntity.class);
            verify(reminderRepository).save(captor.capture());
            assertThat(captor.getValue().getReminderKind()).isEqualTo(ReminderKind.ABSOLUTE);
            // JSTオフセットなのでJST変換後も同じ日時（08:00 JST = 08:00 JST）
            LocalDateTime expected = remindAt.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();
            assertThat(captor.getValue().getRemindAt()).isEqualTo(expected);
            assertThat(captor.getValue().getRemindBeforeMinutes()).isNull();
        }

        @Test
        @DisplayName("絶対指定（UTC: +00:00）_JSTのLocalDateTimeに変換されて保存される")
        void 絶対指定_UTCオフセット_JSTLocalDateTimeで保存される() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // UTC（+00:00）で2026-06-05 00:00 → JST変換後は 09:00
            OffsetDateTime remindAtUtc = OffsetDateTime.of(2026, 6, 5, 0, 0, 0, 0, ZoneOffset.UTC);
            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(remindAtUtc, null, ReminderKind.ABSOLUTE));

            // when
            reminderService.createReminders(SCHEDULE_ID, requests);

            // then: UTC 00:00 → JST 09:00 に変換されて保存される
            ArgumentCaptor<ScheduleAttendanceReminderEntity> captor =
                    ArgumentCaptor.forClass(ScheduleAttendanceReminderEntity.class);
            verify(reminderRepository).save(captor.capture());
            assertThat(captor.getValue().getReminderKind()).isEqualTo(ReminderKind.ABSOLUTE);
            LocalDateTime expectedJst = LocalDateTime.of(2026, 6, 5, 9, 0, 0);
            assertThat(captor.getValue().getRemindAt()).isEqualTo(expectedJst);
        }

        @Test
        @DisplayName("絶対指定（EST: -05:00）_JSTのLocalDateTimeに変換されて保存される")
        void 絶対指定_ESTオフセット_JSTLocalDateTimeで保存される() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // EST（-05:00）で2026-06-05 08:00 → UTC 13:00 → JST 22:00
            OffsetDateTime remindAtEst = OffsetDateTime.of(2026, 6, 5, 8, 0, 0, 0, ZoneOffset.ofHours(-5));
            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(remindAtEst, null, ReminderKind.ABSOLUTE));

            // when
            reminderService.createReminders(SCHEDULE_ID, requests);

            // then: EST 08:00 → JST 22:00 に変換されて保存される
            ArgumentCaptor<ScheduleAttendanceReminderEntity> captor =
                    ArgumentCaptor.forClass(ScheduleAttendanceReminderEntity.class);
            verify(reminderRepository).save(captor.capture());
            LocalDateTime expectedJst = LocalDateTime.of(2026, 6, 5, 22, 0, 0);
            assertThat(captor.getValue().getRemindAt()).isEqualTo(expectedJst);
        }

        @Test
        @DisplayName("相対指定_remindBeforeMinutesが保存されremindAtはnull")
        void 相対指定_remindBeforeMinutesが保存されremindAtはnull() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(null, 30, ReminderKind.RELATIVE));

            // when
            reminderService.createReminders(SCHEDULE_ID, requests);

            // then
            ArgumentCaptor<ScheduleAttendanceReminderEntity> captor =
                    ArgumentCaptor.forClass(ScheduleAttendanceReminderEntity.class);
            verify(reminderRepository).save(captor.capture());
            assertThat(captor.getValue().getReminderKind()).isEqualTo(ReminderKind.RELATIVE);
            assertThat(captor.getValue().getRemindBeforeMinutes()).isEqualTo(30);
            assertThat(captor.getValue().getRemindAt()).isNull();
        }

        @Test
        @DisplayName("リマインダー作成_上限超過_例外スロー")
        void リマインダー作成_上限超過_例外スロー() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(4L);
            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1), null, ReminderKind.ABSOLUTE),
                    new CreateReminderRequest(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(2), null, ReminderKind.ABSOLUTE));

            // when & then
            assertThatThrownBy(() -> reminderService.createReminders(SCHEDULE_ID, requests))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.MAX_REMINDERS_EXCEEDED);
        }
    }

    // ========================================
    // sendReminder
    // ========================================

    @Nested
    @DisplayName("sendReminder")
    class SendReminder {

        @Test
        @DisplayName("出欠必須_未回答者あり_通知イベント発火")
        void 出欠必須_未回答者あり_通知イベント発火() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), true)));
            ScheduleAttendanceEntity undecided = ScheduleAttendanceEntity.builder()
                    .scheduleId(SCHEDULE_ID)
                    .userId(100L)
                    .status(AttendanceStatus.UNDECIDED)
                    .build();
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of(undecided));
            stubMessages();

            // when
            reminderService.sendReminder(SCHEDULE_ID);

            // then
            ArgumentCaptor<ReminderNotificationEvent> captor =
                    ArgumentCaptor.forClass(ReminderNotificationEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getRecipientUserIds()).containsExactly(100L);
            assertThat(captor.getValue().getScheduleId()).isEqualTo(SCHEDULE_ID);
        }

        @Test
        @DisplayName("出欠不要_全出欠対象者へ通知イベント発火")
        void 出欠不要_全出欠対象者へ通知イベント発火() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), false)));
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                    .willReturn(List.of(
                            ScheduleAttendanceEntity.builder().scheduleId(SCHEDULE_ID).userId(1L).build(),
                            ScheduleAttendanceEntity.builder().scheduleId(SCHEDULE_ID).userId(2L).build()));
            stubMessages();

            // when
            reminderService.sendReminder(SCHEDULE_ID);

            // then
            ArgumentCaptor<ReminderNotificationEvent> captor =
                    ArgumentCaptor.forClass(ReminderNotificationEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getRecipientUserIds()).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("対象者なし_イベント発火しない")
        void 対象者なし_イベント発火しない() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), true)));
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of());

            // when
            reminderService.sendReminder(SCHEDULE_ID);

            // then
            verify(eventPublisher, never()).publishEvent(any(ReminderNotificationEvent.class));
        }
    }

    // ========================================
    // processScheduledReminders（実効時刻ベース）
    // ========================================

    @Nested
    @DisplayName("processScheduledReminders")
    class ProcessScheduledReminders {

        private ScheduleAttendanceReminderEntity withId(ScheduleAttendanceReminderEntity reminder, long id) {
            ReflectionTestUtils.setField(reminder, "id", id);
            return reminder;
        }

        @Test
        @DisplayName("ABSOLUTE_due到来_送信済みにマークされる")
        void ABSOLUTE_due到来_送信済みにマークされる() {
            // given: remindAt は過去 → due（SQL 側の絞り込みは findDuePage が担うため、
            // ここでは「due として返ってきたものを処理できるか」のみ検証する）
            ScheduleAttendanceReminderEntity reminder =
                    withId(createAbsoluteReminderEntity(LocalDateTime.now().minusMinutes(5)), 1L);
            given(reminderRepository.findDuePage(any(), any(), any(Pageable.class)))
                    .willReturn(List.of(reminder), List.of());
            given(reminderRepository.findById(1L)).willReturn(Optional.of(reminder));
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), true)));
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of());

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository).save(any(ScheduleAttendanceReminderEntity.class));
            assertThat(reminder.getIsSent()).isTrue();
        }

        @Test
        @DisplayName("RELATIVE_開始N分前到来_送信済みにマークされる")
        void RELATIVE_開始N分前到来_送信済みにマークされる() {
            // given: 開始は5分後・30分前リマインド → 実効時刻は過去 → due
            ScheduleAttendanceReminderEntity reminder = withId(createRelativeReminderEntity(30), 1L);
            given(reminderRepository.findDuePage(any(), any(), any(Pageable.class)))
                    .willReturn(List.of(reminder), List.of());
            given(reminderRepository.findById(1L)).willReturn(Optional.of(reminder));
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusMinutes(5), true)));
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of());

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository).save(any(ScheduleAttendanceReminderEntity.class));
            assertThat(reminder.getIsSent()).isTrue();
        }

        @Test
        @DisplayName("1件送信失敗_例外を握り潰さず記録し後続に影響しない")
        void 送信失敗_例外を握り潰さず記録し後続に影響しない() {
            // given: 1ページ目に2件。1件目の送信元スケジュールが解決不能（想定外の例外）でも
            // 2件目は継続して処理されることを検証する。
            ScheduleAttendanceReminderEntity broken = withId(
                    createAbsoluteReminderEntity(LocalDateTime.now().minusMinutes(5)), 1L);
            ScheduleAttendanceReminderEntity ok = withId(
                    createAbsoluteReminderEntity(LocalDateTime.now().minusMinutes(5)), 2L);
            given(reminderRepository.findDuePage(any(), any(), any(Pageable.class)))
                    .willReturn(List.of(broken, ok), List.of());
            given(reminderRepository.findById(1L)).willReturn(Optional.of(broken));
            given(reminderRepository.findById(2L)).willReturn(Optional.of(ok));
            // broken 側の scheduleId 解決で例外発生
            given(scheduleRepository.findById(broken.getScheduleId()))
                    .willThrow(new IllegalStateException("想定外エラー"));

            // ok は broken と scheduleId が同一のためスケジュール解決を分岐できない前提を避け、
            // 別スケジュールIDに差し替える。
            ReflectionTestUtils.setField(ok, "scheduleId", 2L);
            given(scheduleRepository.findById(2L))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), false, 2L)));
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(2L)).willReturn(List.of());

            // when
            reminderService.processScheduledReminders();

            // then: broken は保存されず、ok のみ保存・送信済み化される
            assertThat(broken.getIsSent()).isFalse();
            assertThat(ok.getIsSent()).isTrue();
            verify(reminderRepository).save(ok);
        }

        @Test
        @DisplayName("未送信なし_何もしない")
        void 未送信なし_何もしない() {
            // given
            given(reminderRepository.findDuePage(any(), any(), any(Pageable.class))).willReturn(List.of());

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository, never()).save(any(ScheduleAttendanceReminderEntity.class));
            verify(eventPublisher, never()).publishEvent(any(ReminderNotificationEvent.class));
        }
    }
}
