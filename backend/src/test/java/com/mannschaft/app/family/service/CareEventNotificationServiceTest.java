package com.mannschaft.app.family.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.event.entity.EventCareNotificationLogEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventCareNotificationLogRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.EventCareNotificationType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CareEventNotificationService 単体テスト。F03.12 Phase3。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CareEventNotificationService 単体テスト")
class CareEventNotificationServiceTest {

    @Mock
    private CareLinkService careLinkService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationDispatchService dispatchService;

    @Mock
    private EventCareNotificationLogRepository notificationLogRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CareEventNotificationService service;

    // =========================================================
    // notifyRsvpConfirmed
    // =========================================================

    @Nested
    @DisplayName("notifyRsvpConfirmed")
    class NotifyRsvpConfirmed {

        @Test
        @DisplayName("ケア対象外ユーザーはスキップ")
        void shouldSkipWhenNotUnderCare() {
            given(careLinkService.isUnderCare(1L)).willReturn(false);

            service.notifyRsvpConfirmed(1L, 100L);

            verify(notificationService, never()).createNotification(
                    anyLong(), anyString(), any(), anyString(), anyString(),
                    anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("見守り者が存在しない場合はスキップ")
        void shouldSkipWhenNoActiveWatchers() {
            given(careLinkService.isUnderCare(1L)).willReturn(true);
            given(careLinkService.getActiveWatchers(1L, "RSVP")).willReturn(Collections.emptyList());

            service.notifyRsvpConfirmed(1L, 100L);

            verify(notificationService, never()).createNotification(
                    anyLong(), anyString(), any(), anyString(), anyString(),
                    anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("正常に見守り者へ通知を送信する")
        void shouldSendNotificationToWatcher() {
            Long recipientUserId = 1L;
            Long watcherUserId = 2L;
            Long eventId = 100L;

            UserEntity recipientUser = buildUser(recipientUserId, "山田太郎", CareCategory.MINOR);
            EventEntity event = buildEvent(eventId, "子ども会イベント", "kodomo-event");
            NotificationEntity notification = buildNotification(recipientUserId);

            given(careLinkService.isUnderCare(recipientUserId)).willReturn(true);
            given(careLinkService.getActiveWatchers(recipientUserId, "RSVP")).willReturn(List.of(watcherUserId));
            given(notificationLogRepository.existsByEventIdAndCareRecipientUserIdAndWatcherUserIdAndNotificationType(
                    eventId, recipientUserId, watcherUserId, EventCareNotificationType.RSVP_CONFIRMED)).willReturn(false);
            given(eventRepository.findById(eventId)).willReturn(Optional.of(event));
            given(userRepository.findById(recipientUserId)).willReturn(Optional.of(recipientUser));
            given(notificationService.createNotification(
                    eq(watcherUserId), eq(EventCareNotificationType.RSVP_CONFIRMED.name()),
                    eq(NotificationPriority.NORMAL), anyString(), anyString(),
                    eq("EVENT"), eq(eventId),
                    eq(NotificationScopeType.PERSONAL), eq(watcherUserId),
                    anyString(), eq(recipientUserId))).willReturn(notification);

            service.notifyRsvpConfirmed(recipientUserId, eventId);

            verify(notificationService).createNotification(
                    eq(watcherUserId), eq(EventCareNotificationType.RSVP_CONFIRMED.name()),
                    eq(NotificationPriority.NORMAL), anyString(), anyString(),
                    eq("EVENT"), eq(eventId),
                    eq(NotificationScopeType.PERSONAL), eq(watcherUserId),
                    anyString(), eq(recipientUserId));
            verify(dispatchService).dispatch(notification);
            verify(notificationLogRepository).save(any(EventCareNotificationLogEntity.class));
        }

        @Test
        @DisplayName("冪等チェック：既存ログがある場合はスキップ")
        void shouldSkipWhenAlreadyLogged() {
            Long recipientUserId = 1L;
            Long watcherUserId = 2L;
            Long eventId = 100L;

            EventEntity event = buildEvent(eventId, "子ども会イベント", "kodomo-event");
            UserEntity recipientUser = buildUser(recipientUserId, "山田太郎", CareCategory.MINOR);

            given(careLinkService.isUnderCare(recipientUserId)).willReturn(true);
            given(careLinkService.getActiveWatchers(recipientUserId, "RSVP")).willReturn(List.of(watcherUserId));
            given(eventRepository.findById(eventId)).willReturn(Optional.of(event));
            given(userRepository.findById(recipientUserId)).willReturn(Optional.of(recipientUser));
            given(notificationLogRepository.existsByEventIdAndCareRecipientUserIdAndWatcherUserIdAndNotificationType(
                    eventId, recipientUserId, watcherUserId, EventCareNotificationType.RSVP_CONFIRMED)).willReturn(true);

            service.notifyRsvpConfirmed(recipientUserId, eventId);

            verify(notificationService, never()).createNotification(
                    anyLong(), anyString(), any(), anyString(), anyString(),
                    anyString(), anyLong(), any(), anyLong(), anyString(), anyLong());
        }
    }

    // =========================================================
    // notifyCheckin
    // =========================================================

    @Nested
    @DisplayName("notifyCheckin")
    class NotifyCheckin {

        @Test
        @DisplayName("正常にチェックイン通知を見守り者へ送信する")
        void shouldSendCheckinNotificationToWatcher() {
            Long recipientUserId = 1L;
            Long watcherUserId = 2L;
            Long eventId = 100L;

            UserEntity recipientUser = buildUser(recipientUserId, "鈴木花子", CareCategory.ELDERLY);
            EventEntity event = buildEvent(eventId, "地域サロン", "chiiki-salon");
            NotificationEntity notification = buildNotification(recipientUserId);

            given(careLinkService.isUnderCare(recipientUserId)).willReturn(true);
            given(careLinkService.getActiveWatchers(recipientUserId, "CHECKIN")).willReturn(List.of(watcherUserId));
            given(notificationLogRepository.existsByEventIdAndCareRecipientUserIdAndWatcherUserIdAndNotificationType(
                    eventId, recipientUserId, watcherUserId, EventCareNotificationType.CHECKIN)).willReturn(false);
            given(eventRepository.findById(eventId)).willReturn(Optional.of(event));
            given(userRepository.findById(recipientUserId)).willReturn(Optional.of(recipientUser));
            given(notificationService.createNotification(
                    eq(watcherUserId), eq(EventCareNotificationType.CHECKIN.name()),
                    eq(NotificationPriority.NORMAL), anyString(), anyString(),
                    eq("EVENT"), eq(eventId),
                    eq(NotificationScopeType.PERSONAL), eq(watcherUserId),
                    anyString(), eq(recipientUserId))).willReturn(notification);

            service.notifyCheckin(recipientUserId, eventId);

            verify(notificationService).createNotification(
                    eq(watcherUserId), eq(EventCareNotificationType.CHECKIN.name()),
                    eq(NotificationPriority.NORMAL), anyString(), anyString(),
                    eq("EVENT"), eq(eventId),
                    eq(NotificationScopeType.PERSONAL), eq(watcherUserId),
                    anyString(), eq(recipientUserId));
            verify(dispatchService).dispatch(notification);
        }
    }

    // =========================================================
    // sendNoContactCheck
    // =========================================================

    @Nested
    @DisplayName("sendNoContactCheck")
    class SendNoContactCheck {

        @Test
        @DisplayName("NORMAL優先度で不在確認通知を送信する")
        void shouldSendNoContactCheckWithNormalPriority() {
            Long recipientUserId = 1L;
            Long watcherUserId = 2L;
            Long eventId = 100L;

            UserEntity recipientUser = buildUser(recipientUserId, "佐藤一郎", CareCategory.MINOR);
            EventEntity event = buildEvent(eventId, "サッカー練習", "soccer-practice");
            NotificationEntity notification = buildNotification(recipientUserId);

            given(careLinkService.isUnderCare(recipientUserId)).willReturn(true);
            given(careLinkService.getActiveWatchers(recipientUserId, "ABSENT_ALERT")).willReturn(List.of(watcherUserId));
            given(notificationLogRepository.existsByEventIdAndCareRecipientUserIdAndWatcherUserIdAndNotificationType(
                    eventId, recipientUserId, watcherUserId, EventCareNotificationType.NO_CONTACT_CHECK)).willReturn(false);
            given(eventRepository.findById(eventId)).willReturn(Optional.of(event));
            given(userRepository.findById(recipientUserId)).willReturn(Optional.of(recipientUser));
            given(notificationService.createNotification(
                    eq(watcherUserId), eq(EventCareNotificationType.NO_CONTACT_CHECK.name()),
                    eq(NotificationPriority.NORMAL), anyString(), anyString(),
                    eq("EVENT"), eq(eventId),
                    eq(NotificationScopeType.PERSONAL), eq(watcherUserId),
                    anyString(), eq(recipientUserId))).willReturn(notification);

            service.sendNoContactCheck(recipientUserId, eventId);

            verify(notificationService).createNotification(
                    eq(watcherUserId), eq(EventCareNotificationType.NO_CONTACT_CHECK.name()),
                    eq(NotificationPriority.NORMAL), anyString(), anyString(),
                    eq("EVENT"), eq(eventId),
                    eq(NotificationScopeType.PERSONAL), eq(watcherUserId),
                    anyString(), eq(recipientUserId));
        }
    }

    // =========================================================
    // sendAbsentAlert
    // =========================================================

    @Nested
    @DisplayName("sendAbsentAlert")
    class SendAbsentAlert {

        @Test
        @DisplayName("HIGH優先度で不在アラートを送信する")
        void shouldSendAbsentAlertWithHighPriority() {
            Long recipientUserId = 1L;
            Long watcherUserId = 2L;
            Long eventId = 100L;

            UserEntity recipientUser = buildUser(recipientUserId, "田中二郎", CareCategory.MINOR);
            EventEntity event = buildEvent(eventId, "野球大会", "baseball-tournament");
            NotificationEntity notification = buildNotification(recipientUserId);

            given(careLinkService.isUnderCare(recipientUserId)).willReturn(true);
            given(careLinkService.getActiveWatchers(recipientUserId, "ABSENT_ALERT")).willReturn(List.of(watcherUserId));
            given(notificationLogRepository.existsByEventIdAndCareRecipientUserIdAndWatcherUserIdAndNotificationType(
                    eventId, recipientUserId, watcherUserId, EventCareNotificationType.ABSENT_ALERT)).willReturn(false);
            given(eventRepository.findById(eventId)).willReturn(Optional.of(event));
            given(userRepository.findById(recipientUserId)).willReturn(Optional.of(recipientUser));
            given(notificationService.createNotification(
                    eq(watcherUserId), eq(EventCareNotificationType.ABSENT_ALERT.name()),
                    eq(NotificationPriority.HIGH), anyString(), anyString(),
                    eq("EVENT"), eq(eventId),
                    eq(NotificationScopeType.PERSONAL), eq(watcherUserId),
                    anyString(), eq(recipientUserId))).willReturn(notification);

            service.sendAbsentAlert(recipientUserId, eventId);

            // HIGH優先度で呼ばれることを確認
            verify(notificationService).createNotification(
                    eq(watcherUserId), eq(EventCareNotificationType.ABSENT_ALERT.name()),
                    eq(NotificationPriority.HIGH), anyString(), anyString(),
                    eq("EVENT"), eq(eventId),
                    eq(NotificationScopeType.PERSONAL), eq(watcherUserId),
                    anyString(), eq(recipientUserId));
            verify(dispatchService).dispatch(notification);
        }
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    private UserEntity buildUser(Long id, String displayName, CareCategory category) {
        return UserEntity.builder()
                .email("test" + id + "@example.com")
                .lastName("姓")
                .firstName("名")
                .displayName(displayName)
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .careCategory(category)
                .build();
    }

    private EventEntity buildEvent(Long id, String subtitle, String slug) {
        return EventEntity.builder()
                .scopeType(com.mannschaft.app.event.EventScopeType.TEAM)
                .scopeId(10L)
                .slug(slug)
                .subtitle(subtitle)
                .build();
    }

    private NotificationEntity buildNotification(Long userId) {
        return NotificationEntity.builder()
                .userId(userId)
                .notificationType(EventCareNotificationType.RSVP_CONFIRMED.name())
                .priority(NotificationPriority.NORMAL)
                .title("テスト通知")
                .body("テスト通知本文")
                .sourceType("EVENT")
                .sourceId(100L)
                .scopeType(NotificationScopeType.PERSONAL)
                .scopeId(userId)
                .actionUrl("/events/100")
                .actorId(userId)
                .build();
    }
}
