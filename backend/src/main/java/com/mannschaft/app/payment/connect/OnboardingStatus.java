package com.mannschaft.app.payment.connect;

/**
 * F22.1 謝礼決済: Connect アカウントの onboarding 状態。
 *
 * <p>{@code connect_accounts.onboarding_status}（VARCHAR(16) + CHECK）に対応する。
 * Stripe アカウントの onboarding ライフサイクルの鏡像。</p>
 */
public enum OnboardingStatus {
    /** リンク発行前。 */
    PENDING,
    /** hosted onboarding 中。 */
    ONBOARDING,
    /** 払出可能。 */
    READY,
    /** 要件不足。 */
    RESTRICTED,
    /** deauthorized（解約）。 */
    DISABLED
}
