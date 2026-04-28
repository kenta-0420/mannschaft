package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.dto.DismissalReminderTargetResponse;
import com.mannschaft.app.event.dto.DismissalRequest;
import com.mannschaft.app.event.dto.DismissalStatusResponse;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRepository.DismissalReminderTargetProjection;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EventDismissalService} のユニットテスト。F03.12 §16。
 */
@ExtendWith(MockitoExtension.class)
class EventDismissalServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventRsvpResponseRepository rsvpResponseRepository;

    @Mock
    private EventCheckinRepository checkinRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationDispatchService dispatchService;

    @Mock
    private CareEventNotificationService careEventNotificationService;

    @Mock
    private CareLinkService careLinkService;

    @InjectMocks
    private EventDismissalService eventDismissalService;

    // テスト定数
    private static final Long EVENT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long OPERATOR_USER_ID = 100L;
    private static final Long ATTENDING_USER_ID_1 = 201L;
    private static final Long ATTENDING_USER_ID_2 = 202L;
    private static final Long CARE_RECIPIENT_USER_ID = 203L;

    // =========================================================
    // sendDismissalNotification
    // =========================================================

    @Nested
    @DisplayName("sendDismissalNotification")
    class SendDismissalNotification {

        @Test
        @DisplayName("正常_ATTENDINGメンバー全員に通知: ATTENDING2名+ケア対象1名 → 通知3件 + notifyDismissal呼び出し")
        void 正常_ATTENDINGメンバー全員に通知() {
            // Arrange
            EventEntity event = buildEventWithoutDismissal();
            DismissalRequest req = new DismissalRequest("解散しました", null, true);

            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));
            given(rsvpResponseRepository.findUserIdsByEventIdAndResponse(EVENT_ID, "ATTENDING"))
                    .willReturn(List.of(ATTENDING_USER_ID_1, ATTENDING_USER_ID_2, CARE_RECIPIENT_USER_ID));
            given(checkinRepository.findCheckedInUserIdsByEventId(EVENT_ID)).willReturn(List.of());
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), isNull()))
                    .willReturn(buildNotification());
            // ケア対象者は1名のみ
            given(careLinkService.isUnderCare(ATTENDING_USER_ID_1)).willReturn(false);
            given(careLinkService.isUnderCare(ATTENDING_USER_ID_2)).willReturn(false);
            given(careLinkService.isUnderCare(CARE_RECIPIENT_USER_ID)).willReturn(true);

            // Act
            eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req);

            // Assert: 参加者3名に通知送信。F03.12 Phase11: actionUrl は /teams/{teamId}/events/{eventId} 形式。
            String expectedActionUrl = "/teams/" + TEAM_ID + "/events/" + EVENT_ID;
            verify(notificationService, times(3)).createNotification(
                    anyLong(), eq("EVENT_DISMISSAL"), any(NotificationPriority.class),
                    any(), any(), eq("EVENT"), eq(EVENT_ID),
                    any(NotificationScopeType.class), anyLong(), eq(expectedActionUrl), isNull());
            verify(dispatchService, times(3)).dispatch(any(NotificationEntity.class));

            // ケア対象者の見守り者にも通知
            verify(careEventNotificationService).notifyDismissal(CARE_RECIPIENT_USER_ID, EVENT_ID);
        }

        @Test
        @DisplayName("正常_notifyGuardians=false: 見守り者への通知を行わない")
        void 正常_notifyGuardians_false() {
            // Arrange
            EventEntity event = buildEventWithoutDismissal();
            DismissalRequest req = new DismissalRequest(null, null, false);

            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));
            given(rsvpResponseRepository.findUserIdsByEventIdAndResponse(EVENT_ID, "ATTENDING"))
                    .willReturn(List.of(ATTENDING_USER_ID_1));
            given(checkinRepository.findCheckedInUserIdsByEventId(EVENT_ID)).willReturn(List.of());
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), isNull()))
                    .willReturn(buildNotification());

            // Act
            eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req);

            // Assert: 見守り者への通知は呼ばれない
            verify(careEventNotificationService, never()).notifyDismissal(anyLong(), anyLong());
            verify(careLinkService, never()).isUnderCare(anyLong());
        }

        @Test
        @DisplayName("重複送信エラー: 既送信イベントに再送 → BusinessException(ALREADY_DISMISSED)")
        void 重複送信エラー() {
            // Arrange: 既に dismissalNotificationSentAt が設定済み
            EventEntity event = buildEventWithDismissal();
            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));

            DismissalRequest req = new DismissalRequest(null, null, true);

            // Act & Assert
            assertThatThrownBy(() ->
                    eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode()).isEqualTo(EventErrorCode.ALREADY_DISMISSED);
                    });

            // 通知は一切送信しない
            verify(notificationService, never()).createNotification(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("イベント未存在: EVENT_NOT_FOUND をスロー")
        void イベント未存在() {
            // Arrange
            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.empty());

            DismissalRequest req = new DismissalRequest(null, null, true);

            // Act & Assert
            assertThatThrownBy(() ->
                    eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode()).isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("チェックインのみ参加者も通知: RSVP未登録・チェックイン済みユーザーにも送信")
        void チェックインのみ参加者も通知() {
            // Arrange
            EventEntity event = buildEventWithoutDismissal();
            // ATTENDING_USER_ID_1 は RSVP のみ、ATTENDING_USER_ID_2 はチェックインのみ
            DismissalRequest req = new DismissalRequest(null, null, false);

            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));
            given(rsvpResponseRepository.findUserIdsByEventIdAndResponse(EVENT_ID, "ATTENDING"))
                    .willReturn(List.of(ATTENDING_USER_ID_1));
            // チェックインのみのユーザー（RSVP 未登録）
            given(checkinRepository.findCheckedInUserIdsByEventId(EVENT_ID))
                    .willReturn(List.of(ATTENDING_USER_ID_2));
            given(notificationService.createNotification(
                    anyLong(), any(), any(NotificationPriority.class),
                    any(), any(), any(), anyLong(),
                    any(NotificationScopeType.class), anyLong(), any(), isNull()))
                    .willReturn(buildNotification());

            // Act
            eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req);

            // Assert: 両名（RSVP + チェックイン）に通知
            verify(notificationService, times(2)).createNotification(
                    anyLong(), eq("EVENT_DISMISSAL"), any(NotificationPriority.class),
                    any(), any(), eq("EVENT"), eq(EVENT_ID),
                    any(NotificationScopeType.class), anyLong(), any(), isNull());
        }
    }

    // =========================================================
    // getDismissalStatus
    // =========================================================

    @Nested
    @DisplayName("getDismissalStatus")
    class GetDismissalStatus {

        @Test
        @DisplayName("未送信: isDismissed=false・dismissalNotificationSentAt=null")
        void 未送信() {
            // Arrange
            EventEntity event = buildEventWithoutDismissal();
            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));

            // Act
            DismissalStatusResponse response = eventDismissalService.getDismissalStatus(EVENT_ID, TEAM_ID);

            // Assert
            assertThat(response.isDismissed()).isFalse();
            assertThat(response.getDismissalNotificationSentAt()).isNull();
            assertThat(response.getDismissalNotifiedByUserId()).isNull();
            assertThat(response.getReminderCount()).isZero();
            assertThat(response.getLastReminderAt()).isNull();
        }

        @Test
        @DisplayName("送信済み: isDismissed=true・dismissalNotificationSentAt が設定済み")
        void 送信済み() {
            // Arrange
            EventEntity event = buildEventWithDismissal();
            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));

            // Act
            DismissalStatusResponse response = eventDismissalService.getDismissalStatus(EVENT_ID, TEAM_ID);

            // Assert
            assertThat(response.isDismissed()).isTrue();
            assertThat(response.getDismissalNotificationSentAt()).isNotNull();
            assertThat(response.getDismissalNotifiedByUserId()).isEqualTo(OPERATOR_USER_ID);
        }
    }

    // =========================================================
    // getMyDismissalReminderTargets (F03.12 Phase11)
    // =========================================================

    @Nested
    @DisplayName("getMyDismissalReminderTargets")
    class GetMyDismissalReminderTargets {

        @Test
        @DisplayName("正常_主催未解散イベントを DTO 化して返す: 投影 → DTO 変換 + minutesPassed/teamName/eventName 解決")
        void 正常_主催未解散イベントを返す() {
            // Arrange
            LocalDateTime endAt = LocalDateTime.now().minusMinutes(45);
            DismissalReminderTargetProjection projection = buildProjection(
                    EVENT_ID, "テスト解散イベント", "test-event", TEAM_ID, "テストチーム",
                    endAt, (byte) 1);
            given(eventRepository.findMyOrganizingUndismissedExpiredEvents(
                    eq(OPERATOR_USER_ID), any(LocalDateTime.class)))
                    .willReturn(List.of(projection));

            // Act
            List<DismissalReminderTargetResponse> result =
                    eventDismissalService.getMyDismissalReminderTargets(OPERATOR_USER_ID);

            // Assert
            assertThat(result).hasSize(1);
            DismissalReminderTargetResponse dto = result.get(0);
            assertThat(dto.getEventId()).isEqualTo(EVENT_ID);
            assertThat(dto.getEventName()).isEqualTo("テスト解散イベント");
            assertThat(dto.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(dto.getTeamName()).isEqualTo("テストチーム");
            assertThat(dto.getEndAt()).isEqualTo(endAt);
            // 経過分数は 45分前後（バッファ ±2 分）
            assertThat(dto.getMinutesPassed()).isBetween(43L, 47L);
            assertThat(dto.getReminderCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("subtitle が空の場合は slug を fallback として使う")
        void subtitle空_slug_fallback() {
            // Arrange
            LocalDateTime endAt = LocalDateTime.now().minusMinutes(60);
            DismissalReminderTargetProjection projection = buildProjection(
                    EVENT_ID, null, "fallback-slug", TEAM_ID, "チームA",
                    endAt, (byte) 0);
            given(eventRepository.findMyOrganizingUndismissedExpiredEvents(
                    eq(OPERATOR_USER_ID), any(LocalDateTime.class)))
                    .willReturn(List.of(projection));

            // Act
            List<DismissalReminderTargetResponse> result =
                    eventDismissalService.getMyDismissalReminderTargets(OPERATOR_USER_ID);

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getEventName()).isEqualTo("fallback-slug");
            assertThat(result.get(0).getReminderCount()).isZero();
        }

        @Test
        @DisplayName("対象0件: 空リストを返す")
        void 対象0件() {
            // Arrange
            given(eventRepository.findMyOrganizingUndismissedExpiredEvents(
                    eq(OPERATOR_USER_ID), any(LocalDateTime.class)))
                    .willReturn(List.of());

            // Act
            List<DismissalReminderTargetResponse> result =
                    eventDismissalService.getMyDismissalReminderTargets(OPERATOR_USER_ID);

            // Assert
            assertThat(result).isEmpty();
        }
    }

    // =========================================================
    // テストヘルパー
    // =========================================================

    /**
     * テスト用の {@link DismissalReminderTargetProjection} を生成する。
     */
    private DismissalReminderTargetProjection buildProjection(Long eventId, String subtitle, String slug,
                                                              Long teamId, String teamName,
                                                              LocalDateTime endAt, Byte reminderCount) {
        return new DismissalReminderTargetProjection() {
            @Override public Long getEventId() { return eventId; }
            @Override public String getSubtitle() { return subtitle; }
            @Override public String getSlug() { return slug; }
            @Override public Long getTeamId() { return teamId; }
            @Override public String getTeamName() { return teamName; }
            @Override public LocalDateTime getEndAt() { return endAt; }
            @Override public Byte getReminderCount() { return reminderCount; }
        };
    }


    /**
     * 解散通知が未送信のイベントエンティティを構築する。
     */
    private EventEntity buildEventWithoutDismissal() {
        return EventEntity.builder()
                .scopeType(com.mannschaft.app.event.EventScopeType.TEAM)
                .scopeId(TEAM_ID)
                .slug("test-event")
                .subtitle("テストイベント")
                .createdBy(OPERATOR_USER_ID)
                .attendanceMode(EventAttendanceMode.RSVP)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .build();
    }

    /**
     * 解散通知が送信済みのイベントエンティティを構築する。
     */
    private EventEntity buildEventWithDismissal() {
        EventEntity event = buildEventWithoutDismissal();
        event.recordDismissal(OPERATOR_USER_ID);
        return event;
    }

    /**
     * テスト用の通知エンティティを構築する。
     */
    private NotificationEntity buildNotification() {
        return NotificationEntity.builder()
                .userId(ATTENDING_USER_ID_1)
                .notificationType("EVENT_DISMISSAL")
                .title("解散通知")
                .body("解散しました")
                .sourceType("EVENT")
                .sourceId(EVENT_ID)
                .scopeType(NotificationScopeType.PERSONAL)
                .scopeId(ATTENDING_USER_ID_1)
                .build();
    }
}
