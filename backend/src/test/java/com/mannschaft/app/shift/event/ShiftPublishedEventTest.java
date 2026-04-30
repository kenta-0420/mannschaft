package com.mannschaft.app.shift.event;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link ShiftPublishedEvent} および {@link ShiftPublishedNotificationListener} のユニットテスト。
 * F03.5 Phase 4-0 不整合 #A 補修。
 */
@ExtendWith(MockitoExtension.class)
class ShiftPublishedEventTest {

    @Mock private NotificationHelper notificationHelper;
    @Mock private UserRoleRepository userRoleRepository;

    @InjectMocks
    private ShiftPublishedNotificationListener listener;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TRIGGERED_BY = 100L;

    // =========================================================
    // ShiftPublishedEvent フィールド検証
    // =========================================================

    @Nested
    @DisplayName("ShiftPublishedEvent")
    class EventFields {

        @Test
        @DisplayName("コンストラクタで渡した値をすべて保持する")
        void フィールド保持() {
            ShiftPublishedEvent event = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY);

            assertThat(event.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(event.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(event.getTriggeredByUserId()).isEqualTo(TRIGGERED_BY);
            assertThat(event.getOccurredAt()).isNotNull();
        }
    }

    // =========================================================
    // ShiftPublishedNotificationListener
    // =========================================================

    @Nested
    @DisplayName("ShiftPublishedNotificationListener")
    class ListenerTests {

        @Test
        @DisplayName("チームメンバー全員に SHIFT_PUBLISHED 通知を送信する")
        void チームメンバー全員に通知() {
            ShiftPublishedEvent event = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY);
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of(101L, 102L, 103L));

            listener.onShiftPublished(event);

            verify(notificationHelper).notifyAll(
                    eq(List.of(101L, 102L, 103L)),
                    eq("SHIFT_PUBLISHED"),
                    anyString(), anyString(),
                    eq("SHIFT_SCHEDULE"), eq(SCHEDULE_ID),
                    eq(NotificationScopeType.TEAM), eq(TEAM_ID),
                    eq("/shifts/schedules/" + SCHEDULE_ID),
                    eq(TRIGGERED_BY));
        }

        @Test
        @DisplayName("チームメンバーが0人の場合は通知しない")
        void メンバー0人は通知なし() {
            ShiftPublishedEvent event = new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, TRIGGERED_BY);
            given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID))
                    .willReturn(List.of());

            listener.onShiftPublished(event);

            verify(notificationHelper, never()).notifyAll(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
