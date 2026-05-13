package com.mannschaft.app.notification.credit.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 通知クレジット Checkout セッション作成リクエスト。
 *
 * @param packageId 購入するパッケージID
 */
public record NotificationCreditCheckoutRequest(
        @NotNull Long packageId
) {}
