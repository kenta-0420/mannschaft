package com.mannschaft.app.notification.credit.event;

/**
 * 通知プリペイドクレジット購入の入金が確定したことを表すドメインイベント（F08.12 §5.2）。
 *
 * <p>ドメイン境界を越えるため<b>ID 参照だけを載せる</b>（設計原則 1・5）。</p>
 *
 * @param purchaseId 入金確定した購入記録の ID
 */
public record NotificationCreditPurchasePaidEvent(Long purchaseId) {
}
