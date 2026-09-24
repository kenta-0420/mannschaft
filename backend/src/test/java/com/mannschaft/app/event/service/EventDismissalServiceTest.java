package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.dto.DismissalReminderTargetResponse;
import com.mannschaft.app.event.dto.DismissalRequest;
import com.mannschaft.app.event.dto.DismissalStatusResponse;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.event.EventDismissalNotificationEvent;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventRepository.DismissalReminderTargetProjection;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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

    /**
     * Issue #2834 / CMP-056 第1群ロットB: 通知は業務コミット後に
     * {@code EventDismissalNotificationListener} が配送するため、本サービスの依存は
     * イベントパブリッシャーのみになった（NotificationService / CareEventNotificationService /
     * UserLocaleCache / MessageSource への依存は配送リスナーへ移動した）。
     */
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

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

        /**
         * Issue #2834 / CMP-056 第1群ロットB: 本サービスは通知を組み立てず、
         * 業務コミット後に配送されるイベントを publish するだけになった。
         * 通知の組み立て・受信者ごとの隔離・locale 別文面の検証は
         * {@code EventDismissalNotificationListenerTest} が担う。
         */
        private EventDismissalNotificationEvent capturePublishedEvent() {
            ArgumentCaptor<EventDismissalNotificationEvent> captor =
                    ArgumentCaptor.forClass(EventDismissalNotificationEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("正常_ATTENDINGメンバー全員が配送イベントに載る: ATTENDING2名+ケア対象1名 → 受信者3名")
        void 正常_ATTENDINGメンバー全員に通知() {
            EventEntity event = buildEventWithoutDismissal();
            DismissalRequest req = new DismissalRequest("解散しました", null, true);

            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));
            given(rsvpResponseRepository.findUserIdsByEventIdAndResponse(EVENT_ID, "ATTENDING"))
                    .willReturn(List.of(ATTENDING_USER_ID_1, ATTENDING_USER_ID_2, CARE_RECIPIENT_USER_ID));
            given(checkinRepository.findCheckedInUserIdsByEventId(EVENT_ID)).willReturn(List.of());

            eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req);

            EventDismissalNotificationEvent published = capturePublishedEvent();
            assertThat(published.eventId()).isEqualTo(EVENT_ID);
            assertThat(published.teamId()).isEqualTo(TEAM_ID);
            assertThat(published.operatorUserId()).isEqualTo(OPERATOR_USER_ID);
            assertThat(published.customMessage()).isEqualTo("解散しました");
            assertThat(published.notifyGuardians()).isTrue();
            assertThat(published.targetUserIds()).containsExactlyInAnyOrder(
                    ATTENDING_USER_ID_1, ATTENDING_USER_ID_2, CARE_RECIPIENT_USER_ID);

            // 解散通知済みの記録は業務トランザクション内で確定する（AC-1）。
            assertThat(event.getDismissalNotificationSentAt()).isNotNull();
            verify(eventRepository).save(event);
        }

        @Test
        @DisplayName("正常_notifyGuardians=false: 配送イベントの notifyGuardians が false になる")
        void 正常_notifyGuardians_false() {
            EventEntity event = buildEventWithoutDismissal();
            DismissalRequest req = new DismissalRequest(null, null, false);

            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));
            given(rsvpResponseRepository.findUserIdsByEventIdAndResponse(EVENT_ID, "ATTENDING"))
                    .willReturn(List.of(ATTENDING_USER_ID_1));
            given(checkinRepository.findCheckedInUserIdsByEventId(EVENT_ID)).willReturn(List.of());

            eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req);

            assertThat(capturePublishedEvent().notifyGuardians()).isFalse();
        }

        @Test
        @DisplayName("重複送信エラー: 既送信イベントに再送 → BusinessException(ALREADY_DISMISSED)・配送イベントも出ない")
        void 重複送信エラー() {
            EventEntity event = buildEventWithDismissal();
            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));

            DismissalRequest req = new DismissalRequest(null, null, true);

            assertThatThrownBy(() ->
                    eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode()).isEqualTo(EventErrorCode.ALREADY_DISMISSED);
                    });

            // AC-2: 業務が失敗した場合は通知の配送要求も作られない。
            verify(applicationEventPublisher, never())
                    .publishEvent(any(EventDismissalNotificationEvent.class));
        }

        @Test
        @DisplayName("イベント未存在: EVENT_NOT_FOUND をスロー・配送イベントも出ない")
        void イベント未存在() {
            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.empty());

            DismissalRequest req = new DismissalRequest(null, null, true);

            assertThatThrownBy(() ->
                    eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode()).isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
                    });

            verify(applicationEventPublisher, never())
                    .publishEvent(any(EventDismissalNotificationEvent.class));
        }

        @Test
        @DisplayName("チェックインのみ参加者も配送対象: RSVP未登録・チェックイン済みユーザーも受信者に入る")
        void チェックインのみ参加者も通知() {
            EventEntity event = buildEventWithoutDismissal();
            DismissalRequest req = new DismissalRequest(null, null, false);

            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));
            given(rsvpResponseRepository.findUserIdsByEventIdAndResponse(EVENT_ID, "ATTENDING"))
                    .willReturn(List.of(ATTENDING_USER_ID_1));
            given(checkinRepository.findCheckedInUserIdsByEventId(EVENT_ID))
                    .willReturn(List.of(ATTENDING_USER_ID_2));

            eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req);

            assertThat(capturePublishedEvent().targetUserIds())
                    .containsExactlyInAnyOrder(ATTENDING_USER_ID_1, ATTENDING_USER_ID_2);
        }

        @Test
        @DisplayName("参加者ゼロなら配送イベントを publish しない")
        void 参加者ゼロなら配送イベントを出さない() {
            EventEntity event = buildEventWithoutDismissal();
            DismissalRequest req = new DismissalRequest(null, null, false);

            given(eventRepository.findByIdAndTeamScopeId(EVENT_ID, TEAM_ID)).willReturn(Optional.of(event));
            given(rsvpResponseRepository.findUserIdsByEventIdAndResponse(EVENT_ID, "ATTENDING"))
                    .willReturn(List.of());
            given(checkinRepository.findCheckedInUserIdsByEventId(EVENT_ID)).willReturn(List.of());

            eventDismissalService.sendDismissalNotification(EVENT_ID, TEAM_ID, OPERATOR_USER_ID, req);

            verify(applicationEventPublisher, never())
                    .publishEvent(any(EventDismissalNotificationEvent.class));
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
}
