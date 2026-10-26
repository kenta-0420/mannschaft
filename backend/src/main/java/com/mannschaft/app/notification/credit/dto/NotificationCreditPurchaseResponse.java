package com.mannschaft.app.notification.credit.dto;

import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 通知クレジット購入履歴レスポンス。
 *
 * @param id             購入ID
 * @param packageName    パッケージ名（購入時点のスナップショット）
 * @param creditsGranted 付与通数
 * @param priceJpy       支払い金額（日本円）
 * @param paymentStatus  決済ステータス
 * @param paidAt         決済完了日時
 * @param expiresAt      有効期限
 */
public record NotificationCreditPurchaseResponse(
        Long id,
        String packageName,
        Long creditsGranted,
        BigDecimal priceJpy,
        NotificationCreditPurchaseStatus paymentStatus,
        LocalDateTime paidAt,
        LocalDateTime expiresAt
) {}
