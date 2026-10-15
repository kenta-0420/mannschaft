package com.mannschaft.app.notification.listener;

import com.mannschaft.app.auth.event.NewDeviceLoginEvent;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewDeviceLoginNotificationListenerTest {

    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private ValkeyRateLimiter rateLimiter;
    @Mock
    private MessageSource messageSource;
    @InjectMocks
    private NewDeviceLoginNotificationListener listener;

    @Test
    void 本人だけにlocale済み通知を送る() {
        when(rateLimiter.tryConsume(anyString(), anyString(), eq(3), any()))
                .thenReturn(new RateLimitResult(true, 3, 2, 0, 0));
        when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));

        listener.onNewDeviceLogin(new NewDeviceLoginEvent(10L, "192.0.2.1", "fp", "Chrome", "en"));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notify(eq(10L), eq(NotificationType.NEW_DEVICE_LOGIN.name()),
                eq(NotificationType.NEW_DEVICE_LOGIN.getPriority()), anyString(), body.capture(),
                eq("USER"), eq(null), eq(NotificationScopeType.PERSONAL), eq(10L),
                eq("/account/sessions"), eq(null));
        assertThat(body.getValue()).doesNotContain("192.0.2.1");
    }

    @Test
    void 上限超過時は通知を送らない() {
        when(rateLimiter.tryConsume(anyString(), anyString(), eq(3), any()))
                .thenReturn(new RateLimitResult(false, 3, 0, 0, 0));

        listener.onNewDeviceLogin(new NewDeviceLoginEvent(10L, "192.0.2.1", "fp", "Chrome", "ja"));

        verify(notificationHelper, never()).notify(any(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any());
    }
}
