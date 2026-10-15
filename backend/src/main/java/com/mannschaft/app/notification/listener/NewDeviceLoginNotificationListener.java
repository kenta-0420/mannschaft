package com.mannschaft.app.notification.listener;

import com.mannschaft.app.auth.event.NewDeviceLoginEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.Locale;

/**
 * 新規デバイスログインの本人通知を、ログイン処理の commit 後に配送する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewDeviceLoginNotificationListener {

    static final String RATE_LIMIT_ZONE = "auth:new-device-login";
    static final int RATE_LIMIT = 3;
    static final Duration RATE_LIMIT_WINDOW = Duration.ofHours(1);
    private static final String ACTION_URL = "/account/sessions";

    private final NotificationHelper notificationHelper;
    private final ValkeyRateLimiter rateLimiter;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "新規デバイスログインの本人通知はアカウント侵害の早期検知に必要であり、機能フラグで停止しない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewDeviceLogin(NewDeviceLoginEvent event) {
        RateLimitResult result = rateLimiter.tryConsume(
                RATE_LIMIT_ZONE, "u:" + event.getUserId(), RATE_LIMIT, RATE_LIMIT_WINDOW);
        if (!result.allowed()) {
            log.warn("新規デバイス通知をレート制限で抑止: userId={}", event.getUserId());
            return;
        }

        Locale locale = Locale.forLanguageTag(
                event.getLocale() == null || event.getLocale().isBlank() ? "ja" : event.getLocale());
        String deviceName = event.getDeviceName() == null || event.getDeviceName().isBlank()
                ? "Unknown device" : event.getDeviceName();
        String title = messageSource.getMessage(
                "notification.new_device_login.title", null, "New Device Login", locale);
        String body = messageSource.getMessage(
                "notification.new_device_login.body", new Object[]{deviceName},
                "Login from " + deviceName + ". Check your session settings if you don't recognize this device.", locale);

        try {
            notificationHelper.notify(event.getUserId(), NotificationType.NEW_DEVICE_LOGIN.name(),
                    NotificationType.NEW_DEVICE_LOGIN.getPriority(), title, body,
                    NotificationType.NEW_DEVICE_LOGIN.getSourceType(), null,
                    NotificationScopeType.PERSONAL, event.getUserId(), ACTION_URL, null);
        } catch (RuntimeException ex) {
            log.error("新規デバイス通知の配送に失敗: userId={}", event.getUserId(), ex);
        }
    }
}
