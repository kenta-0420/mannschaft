package com.mannschaft.app.payment;

import java.math.BigDecimal;

/**
 * F22.1 市（Market）統一決済 R1: 手数料パターン（率%＋固定額¥）の値オブジェクト（純粋・不変）。
 *
 * <p>{@code fee_policies} の 1 行（解決済み）を表す。{@link PaymentFeeCalculator} に注入して
 * 手数料内訳を計算する（DB 参照は {@link FeePolicyResolver} に閉じ、本値オブジェクト・Calculator は
 * 状態・外部依存を持たない純粋関数性を維持する・設計書 02 §3.5）。</p>
 *
 * <ul>
 *   <li>{@code policyKey} — 自然キー（{@code DEFAULT} / {@code RECRUITMENT_HELPER} 等）。
 *       {@code escrow_transactions.fee_policy_key} へ焼き付ける（遡及防止）。</li>
 *   <li>{@code percentRate} — 総手数料の率（{@code 0 ≤ percentRate < 1}・例 {@code 0.0500}＝5%）。</li>
 *   <li>{@code flatFeeMinor} — 総手数料の固定額（円・最小単位・{@code 0} で率のみ）。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F22.1_market/payment/01_data_model.md §3.6 /
 * 02_api_design.md §3.5 / §3.5.1。</p>
 *
 * @param policyKey    手数料パターンの自然キー
 * @param percentRate  総手数料の率（0 以上 1 未満）
 * @param flatFeeMinor 総手数料の固定額（最小通貨単位・0 以上）
 */
public record FeePolicy(String policyKey, BigDecimal percentRate, long flatFeeMinor) {

    /** DEFAULT パターンの自然キー（解決フォールバックの終端・削除不可・設計書 01 §3.6）。 */
    public static final String DEFAULT_KEY = "DEFAULT";

    /** DEFAULT パターンの率（5%・既存挙動と完全一致＝後方互換）。 */
    public static final BigDecimal DEFAULT_PERCENT_RATE = new BigDecimal("0.0500");

    /**
     * 値の妥当性を検証する（生成時）。
     *
     * @throws IllegalArgumentException policyKey が空、percentRate が範囲外（0 未満/1 以上）、flatFeeMinor が負のとき
     */
    public FeePolicy {
        if (policyKey == null || policyKey.isBlank()) {
            throw new IllegalArgumentException("policyKey must not be blank");
        }
        if (percentRate == null
                || percentRate.signum() < 0
                || percentRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("percentRate must be in [0, 1): " + percentRate);
        }
        if (flatFeeMinor < 0L) {
            throw new IllegalArgumentException("flatFeeMinor must be >= 0: " + flatFeeMinor);
        }
    }

    /**
     * DEFAULT パターン（率5%＋固定0）を返す。{@link PaymentFeeCalculator#calculate(long)}（既定 calculate）が
     * 暗黙に用いるフォールバック値であり、{@link FeePolicyResolver} の解決終端と同値（設計書 01 §3.6）。
     *
     * @return DEFAULT の FeePolicy（率0.05・固定0）
     */
    public static FeePolicy defaultPolicy() {
        return new FeePolicy(DEFAULT_KEY, DEFAULT_PERCENT_RATE, 0L);
    }
}
