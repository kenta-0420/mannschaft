package com.mannschaft.app.notification.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationAnonymizationEventListener")
class NotificationAnonymizationEventListenerTest {

    @Mock
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Mock
    private NotificationPreferenceRepository notificationPreferenceRepository;

    @Mock
    private NotificationTypePreferenceRepository notificationTypePreferenceRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationAnonymizationEventListener listener;

    @Nested
    @DisplayName("handleUserAnonymized")
    class HandleUserAnonymized {

        @Test
        @DisplayName("正常系: プッシュ購読・通知設定・通知種別設定・通知本体が削除される")
        void deletesAllNotificationData() {
            Long userId = 30L;
            var event = new UserAnonymizedEvent(userId, "user@example.com");

            listener.handleUserAnonymized(event);

            verify(pushSubscriptionRepository).deleteByUserId(userId);
            verify(notificationPreferenceRepository).deleteByUserId(userId);
            verify(notificationTypePreferenceRepository).deleteByUserId(userId);
            // 第二陣E: 通知本体（PII）も即時削除される
            verify(notificationRepository).deleteByUserId(userId);
        }

        @Test
        @DisplayName("例外系: Repositoryが例外を投げてもRuntimeExceptionを外に伝播させない")
        void doesNotPropagateException() {
            Long userId = 99L;
            var event = new UserAnonymizedEvent(userId, "fail@example.com");
            doThrow(new RuntimeException("DB error")).when(pushSubscriptionRepository).deleteByUserId(userId);

            assertDoesNotThrow(() -> listener.handleUserAnonymized(event));
        }
    }
}
