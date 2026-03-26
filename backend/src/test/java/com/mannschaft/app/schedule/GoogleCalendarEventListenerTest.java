package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.UserCalendarSyncSettingEntity;
import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import com.mannschaft.app.schedule.event.ScheduleCancelledEvent;
import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.event.ScheduleUpdatedEvent;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.service.GoogleCalendarEventListener;
import com.mannschaft.app.schedule.service.GoogleCalendarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link GoogleCalendarEventListener} の単体テスト。
 * スケジュールイベント受信時のGoogle Calendar同期処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleCalendarEventListener 単体テスト")
class GoogleCalendarEventListenerTest {

    @Mock
    private GoogleCalendarService googleCalendarService;

    @Mock
    private UserCalendarSyncSettingRepository syncSettingRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private UserGoogleCalendarConnectionRepository connectionRepository;

    @InjectMocks
    private GoogleCalendarEventListener eventListener;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 100L;

    private ScheduleEntity createTeamSchedule() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("練習")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 12, 0))
                .allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .isException(false)
                .build();
    }

    // ========================================
    // onScheduleCreated
    // ========================================

    @Nested
    @DisplayName("onScheduleCreated")
    class OnScheduleCreated {

        @Test
        @DisplayName("スケジュール作成イベント_同期対象あり_同期される")
        void スケジュール作成イベント_同期対象あり_同期される() {
            // given
            ScheduleCreatedEvent event = new ScheduleCreatedEvent(SCHEDULE_ID, "TEAM", TEAM_ID, USER_ID, true);
            ScheduleEntity schedule = createTeamSchedule();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

            UserCalendarSyncSettingEntity syncSetting = UserCalendarSyncSettingEntity.builder()
                    .userId(USER_ID)
                    .scopeType("TEAM")
                    .scopeId(TEAM_ID)
                    .isEnabled(true)
                    .build();
            given(syncSettingRepository.findByScopeTypeAndScopeIdAndIsEnabledTrue("TEAM", TEAM_ID))
                    .willReturn(List.of(syncSetting));

            // when
            eventListener.onScheduleCreated(event);

            // then
            verify(googleCalendarService).syncScheduleToGoogle(schedule, USER_ID);
        }

        @Test
        @DisplayName("スケジュール作成イベント_スケジュール不在_同期されない")
        void スケジュール作成イベント_スケジュール不在_同期されない() {
            // given
            ScheduleCreatedEvent event = new ScheduleCreatedEvent(SCHEDULE_ID, "TEAM", TEAM_ID, USER_ID, true);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // when
            eventListener.onScheduleCreated(event);

            // then
            verify(googleCalendarService, never()).syncScheduleToGoogle(any(), any());
        }

        @Test
        @DisplayName("スケジュール作成イベント_PERSONALスコープ_個人同期有効なら同期される")
        void スケジュール作成イベント_PERSONALスコープ_個人同期有効なら同期される() {
            // given
            ScheduleCreatedEvent event = new ScheduleCreatedEvent(SCHEDULE_ID, "PERSONAL", USER_ID, USER_ID, false);
            ScheduleEntity personalSchedule = ScheduleEntity.builder()
                    .userId(USER_ID)
                    .title("個人予定")
                    .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                    .endAt(LocalDateTime.of(2026, 4, 1, 12, 0))
                    .allDay(false)
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ADMIN_ONLY)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .build();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(personalSchedule));

            UserGoogleCalendarConnectionEntity conn = UserGoogleCalendarConnectionEntity.builder()
                    .userId(USER_ID)
                    .googleAccountEmail("test@gmail.com")
                    .accessToken("enc")
                    .refreshToken("enc")
                    .tokenExpiresAt(LocalDateTime.now().plusHours(1))
                    .isActive(true)
                    .personalSyncEnabled(true)
                    .build();
            given(connectionRepository.findByUserIdAndIsActiveTrue(USER_ID)).willReturn(Optional.of(conn));

            // when
            eventListener.onScheduleCreated(event);

            // then
            verify(googleCalendarService).syncScheduleToGoogle(personalSchedule, USER_ID);
        }
    }

    // ========================================
    // onScheduleUpdated
    // ========================================

    @Nested
    @DisplayName("onScheduleUpdated")
    class OnScheduleUpdated {

        @Test
        @DisplayName("スケジュール更新イベント_正常_同期される")
        void スケジュール更新イベント_正常_同期される() {
            // given
            ScheduleUpdatedEvent event = new ScheduleUpdatedEvent(SCHEDULE_ID, USER_ID);
            ScheduleEntity schedule = createTeamSchedule();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

            UserCalendarSyncSettingEntity syncSetting = UserCalendarSyncSettingEntity.builder()
                    .userId(USER_ID)
                    .scopeType("TEAM")
                    .scopeId(TEAM_ID)
                    .isEnabled(true)
                    .build();
            given(syncSettingRepository.findByScopeTypeAndScopeIdAndIsEnabledTrue("TEAM", TEAM_ID))
                    .willReturn(List.of(syncSetting));

            // when
            eventListener.onScheduleUpdated(event);

            // then
            verify(googleCalendarService).syncScheduleToGoogle(schedule, USER_ID);
        }
    }

    // ========================================
    // onScheduleCancelled
    // ========================================

    @Nested
    @DisplayName("onScheduleCancelled")
    class OnScheduleCancelled {

        @Test
        @DisplayName("スケジュールキャンセルイベント_正常_同期される")
        void スケジュールキャンセルイベント_正常_同期される() {
            // given
            ScheduleCancelledEvent event = new ScheduleCancelledEvent(SCHEDULE_ID, USER_ID);
            ScheduleEntity schedule = createTeamSchedule();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

            UserCalendarSyncSettingEntity syncSetting = UserCalendarSyncSettingEntity.builder()
                    .userId(USER_ID)
                    .scopeType("TEAM")
                    .scopeId(TEAM_ID)
                    .isEnabled(true)
                    .build();
            given(syncSettingRepository.findByScopeTypeAndScopeIdAndIsEnabledTrue("TEAM", TEAM_ID))
                    .willReturn(List.of(syncSetting));

            // when
            eventListener.onScheduleCancelled(event);

            // then
            verify(googleCalendarService).syncScheduleToGoogle(schedule, USER_ID);
        }
    }
}
