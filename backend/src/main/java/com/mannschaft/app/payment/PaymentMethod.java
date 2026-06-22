package com.mannschaft.app.payment;

/**
 * 支払い方法。
 *
 * <p>{@link #STRIPE} は Stripe 自動決済（オンライン）由来の支払い。返金・再同期の対象。
 * 残りの値はすべて「オフライン支払い」（ADMIN が手動記録するもの）であり、返金・再同期の対象外。</p>
 *
 * <ul>
 *   <li>{@link #CASH} — 現金手渡し</li>
 *   <li>{@link #BANK_TRANSFER} — 銀行振込</li>
 *   <li>{@link #MANUAL} — その他／不明（手段未指定時の既定値・既存データ互換のため温存）</li>
 * </ul>
 */
public enum PaymentMethod {
    /** Stripe 自動決済（オンライン）。返金・再同期の対象。 */
    STRIPE,
    /** 現金手渡し（オフライン手動記録）。 */
    CASH,
    /** 銀行振込（オフライン手動記録）。 */
    BANK_TRANSFER,
    /** その他／不明（オフライン手動記録の既定値・既存データ互換のため温存）。 */
    MANUAL
}
