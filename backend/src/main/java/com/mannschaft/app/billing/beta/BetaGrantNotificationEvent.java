package com.mannschaft.app.billing.beta;

import com.mannschaft.app.notification.NotificationType;

/** 操作時の翻訳結果と本人宛先だけを保持する、ベータ特典の通知要求。 */
public record BetaGrantNotificationEvent(
        Long recipientUserId, NotificationType type, String title, String body) {
}
