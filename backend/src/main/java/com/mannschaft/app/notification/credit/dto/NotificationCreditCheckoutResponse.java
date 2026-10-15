package com.mannschaft.app.notification.credit.dto;

/**
 * 通知クレジット Checkout セッション作成レスポンス。
 *
 * @param checkoutUrl Stripe Checkout URL（フロントエンドがこの URL へリダイレクトする）
 * @param sessionId   Stripe Checkout Session ID
 */
public record NotificationCreditCheckoutResponse(
        String checkoutUrl,
        String sessionId
) {}
