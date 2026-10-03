package com.mannschaft.app.notification.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * F04.3 グローバル通知設定レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class NotificationSettingsResponse {

    /** 優先度による自動配信。 */
    Boolean priorityAutoDelivery;
}
