package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * SES 通知の内部表現 DTO。
 *
 * <p>F09.6 Phase 8a で入口を HTTP webhook から SQS リスナーへ移行した。
 * {@code directmail/listener/SesNotificationSqsListener} が SNS エンベロープ／
 * SES 通知 JSON をパースして本 DTO を組み立て、{@code SesWebhookService} へ渡す。
 * {@code token}/{@code subscribeURL} は旧 SubscriptionConfirmation 互換のため残置するが
 * SQS 経由では使用しない（SubscriptionConfirmation は HTTPS サブスクリプション特有）。</p>
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
