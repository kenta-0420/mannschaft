package com.mannschaft.app.notification;

import com.mannschaft.app.notification.entity.NotificationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationEntity} のエンティティ単体テスト。
 * JaCoCoでカバーされていないメソッドを補完する。
 */
@DisplayName("NotificationEntity 単体テスト")
class NotificationEntityTest {

    private NotificationEntity createNotification() {
        return NotificationEntity.builder()
                .userId(1L)
                .notificationType("SCHEDULE_REMINDER")
                .priority(NotificationPriority.NORMAL)
                .title("テスト通知")
                .body("テスト本文")
                .sourceType("SCHEDULE")
                .sourceId(10L)
                .scopeType(NotificationScopeType.TEAM)
                .scopeId(5L)
                .actionUrl("/schedules/10")
                .actorId(2L)
                .build();
    }

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("markAsRead_未読通知_既読になる")
        void markAsRead_未読通知_既読になる() {
            // Given
            NotificationEntity notification = createNotification();

            // When
            notification.markAsRead();

            // Then
            assertThat(notification.getIsRead()).isTrue();
            assertThat(notification.getReadAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("markAsUnread")
    class MarkAsUnread {

        @Test
        @DisplayName("markAsUnread_既読通知_未読に戻る")
        void markAsUnread_既読通知_未読に戻る() {
            // Given
            NotificationEntity notification = createNotification();
            notification.markAsRead();

            // When
            notification.markAsUnread();

            // Then
            assertThat(notification.getIsRead()).isFalse();
            assertThat(notification.getReadAt()).isNull();
        }
    }

    @Nested
    @DisplayName("snooze")
    class Snooze {

        @Test
        @DisplayName("snooze_スヌーズ設定_snoozedUntilが設定される")
        void snooze_スヌーズ設定_snoozedUntilが設定される() {
            // Given
            NotificationEntity notification = createNotification();
            LocalDateTime until = LocalDateTime.now().plusHours(2);

            // When
            notification.snooze(until);

            // Then
            assertThat(notification.getSnoozedUntil()).isEqualTo(until);
        }
    }

    @Nested
    @DisplayName("isAlreadyRead")
    class IsAlreadyRead {

        @Test
        @DisplayName("isAlreadyRead_未読_false返却")
        void isAlreadyRead_未読_false返却() {
            // Given
            NotificationEntity notification = createNotification();

            // When
            boolean result = notification.isAlreadyRead();

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("isAlreadyRead_既読_true返却")
        void isAlreadyRead_既読_true返却() {
            // Given
            NotificationEntity notification = createNotification();
            notification.markAsRead();

            // When
            boolean result = notification.isAlreadyRead();

            // Then
            assertThat(result).isTrue();
        }
    }
}
