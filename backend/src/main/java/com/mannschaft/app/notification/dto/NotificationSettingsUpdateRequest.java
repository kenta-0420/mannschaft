package com.mannschaft.app.notification.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.3 グローバル通知設定更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class NotificationSettingsUpdateRequest {

    /** 優先度による自動配信。 */
    @NotNull
    private final Boolean priorityAutoDelivery;
}
