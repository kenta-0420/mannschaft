package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SES Webhook 通知リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SesNotificationRequest {

    private final String type;
    private final String messageId;
    private final String notificationType;
    private final String bounceType;
    private final String message;
    private final String token;
    private final String topicArn;
    private final String subscribeURL;
}
