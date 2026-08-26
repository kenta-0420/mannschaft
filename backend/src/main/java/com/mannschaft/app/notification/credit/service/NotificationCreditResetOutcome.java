package com.mannschaft.app.notification.credit.service;

/**
 * 通知クレジット月次リセット 1 件処理の結果（CMP-035）。
 *
 * @param shouldAlertNegativeBalance 相殺後に残高がマイナスとなり ADMIN アラートを送るべきか
 * @param organizationId             組織 ID
 * @param creditBalance              リセット後のクレジット残高
 */
public record NotificationCreditResetOutcome(boolean shouldAlertNegativeBalance, Long organizationId, Long creditBalance) {
}
