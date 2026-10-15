package com.mannschaft.app.billing;

/**
 * F20.1: {@code feature_catalog.feature_key} の正準定数（設計書 01 §2.1 シード・02 §5 結線先）。
 *
 * <p>結線先（{@code ModuleService} / {@code ReservationNotificationRecipientService} / F09.19 広告非表示）は
 * これらの定数で {@code EntitlementGuard.require(...)} / {@code isEntitled(...)} を呼ぶ。
 * 文字列直書きを避けタイポ由来の fail-safe 拒否事故（AC-18）を防ぐ。</p>
 */
public final class FeatureKeys {

    private FeatureKeys() {
    }

    /** {@code hasPaidPlan} 互換ブリッジ（README §4.1・BASIC/FULL に含める）。カタログ表示は除外。 */
    public static final String LEGACY_PAID_PLAN_BUNDLE = "legacy.paid_plan_bundle";

    /** {@code ModuleService.requiresPaidPlan} の正体（プレミアムモジュール）。 */
    public static final String TEMPLATE_PREMIUM_MODULES = "template.premium_modules";

    /** {@code RESERVATION_029} ゲートの正体（予約通知宛先 4 件目以降）。 */
    public static final String RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED =
            "reservation.notification_recipients_extended";

    /** F09.19 有料プラン広告非表示。 */
    public static final String ADS_HIDE = "ads.hide";

    /** 収益機能の例（ペイウォール開設・{@code category=REVENUE}）。 */
    public static final String MONETIZATION_PAYWALL = "monetization.paywall";

    /** 収益機能の例（会費徴収の開設・F08.9 の入口側・{@code category=REVENUE}）。 */
    public static final String MONETIZATION_MEMBERSHIP_FEE = "monetization.membership_fee";

    /** カタログ表示除外の規約プレフィックス（{@code legacy.} は内部ブリッジ用途・02 §2.1）。 */
    public static final String CATALOG_HIDDEN_PREFIX = "legacy.";

    /** 予約された FREE プランキー（契約行不要の既定プラン）。 */
    public static final String PLAN_FREE = "FREE";
}
