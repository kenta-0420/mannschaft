package com.mannschaft.app.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * F22.1 市（Market）統一決済 P2-b: 手数料折半の計算（純粋関数・正典）。
 *
 * <p>謝礼（RECRUITMENT・エスクローモード）と会費（MEMBERSHIP・即時モード）の双方に<b>共通</b>で適用する
 * 手数料計算を一元化する（数式・係数を散在させない・設計書 02 §3.5）。本クラスは状態を持たず外部依存もない
 * 純粋関数であり、Stripe 通信・DB・Service ロジックは含まない（それらは次波）。</p>
 *
 * <p>手数料率（マスター確定・案あ）: 合計 5% を支払者 2.5% と受取側 2.5% で折半する。</p>
 * <pre>
 * payerFee             = round(faceAmount × 0.025)     // 支払者上乗せ
 * chargeAmount         = faceAmount + payerFee         // 実請求額（= escrow_transactions.amount）
 * applicationFeeAmount = round(faceAmount × 0.05)      // Mannschaft 徴収（総手数料）
 * transferAmount       = chargeAmount − applicationFeeAmount   // 受取側送金額（≈ 額面 − 2.5%）
 * estimatedStripeFee   = round(chargeAmount × stripeFeeRate)   // 参考値（実額は Webhook 記録）
 * estimatedNetProfit   = applicationFeeAmount − estimatedStripeFee  // 参考値（≈ 額面の 1.31%）
 * </pre>
 *
 * <p><b>金額計算は {@link BigDecimal}（{@link RoundingMode#HALF_UP}＝四捨五入）で行い double 誤差を排除する。</b>
 * 当面は JPY（ゼロデシマル通貨）前提で円整数（最小単位）を扱う。将来 USD 等の少数桁通貨に拡張する場合は、
 * 額面・各金額を「最小単位（cent 等）」で受け渡す前提を維持すれば本計算式はそのまま適用できる
 * （通貨ごとの最小単位スケーリングは呼び出し側の責務とする）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §3.5 / §3.5.1。</p>
 */
@Component
public class PaymentFeeCalculator {

    /** 支払者上乗せ率（2.5%）。 */
    private static final BigDecimal PAYER_FEE_RATE = new BigDecimal("0.025");

    /** 総プラットフォーム手数料率（5% = 支払者2.5% + 受取側2.5%）。 */
    private static final BigDecimal TOTAL_FEE_RATE = new BigDecimal("0.05");

    /** Stripe 実手数料率の既定値（参考純益試算用・設定で上書き可）。 */
    public static final double DEFAULT_STRIPE_FEE_RATE = 0.036d;

    /**
     * 額面から手数料内訳を計算する（既定の Stripe 手数料率 {@value #DEFAULT_STRIPE_FEE_RATE} を使用）。
     *
     * @param faceAmount 額面（円整数・最小単位・正値）
     * @return 手数料内訳
     * @throws IllegalArgumentException faceAmount が 0 以下のとき
     */
    public FeeBreakdown calculate(long faceAmount) {
        return calculate(faceAmount, DEFAULT_STRIPE_FEE_RATE);
    }

    /**
     * 額面と Stripe 手数料率から手数料内訳を計算する。
     *
     * @param faceAmount    額面（円整数・最小単位・正値）
     * @param stripeFeeRate Stripe 実手数料率（0 以上 1 未満・参考純益試算用）
     * @return 手数料内訳
     * @throws IllegalArgumentException faceAmount が 0 以下、または stripeFeeRate が範囲外のとき
     */
    public FeeBreakdown calculate(long faceAmount, double stripeFeeRate) {
        if (faceAmount <= 0L) {
            throw new IllegalArgumentException("faceAmount must be positive (円整数): " + faceAmount);
        }
        if (stripeFeeRate < 0d || stripeFeeRate >= 1d) {
            throw new IllegalArgumentException("stripeFeeRate must be in [0, 1): " + stripeFeeRate);
        }

        BigDecimal face = BigDecimal.valueOf(faceAmount);

        long payerFee = roundToUnit(face.multiply(PAYER_FEE_RATE));
        long chargeAmount = faceAmount + payerFee;
        long applicationFeeAmount = roundToUnit(face.multiply(TOTAL_FEE_RATE));
        long transferAmount = chargeAmount - applicationFeeAmount;

        long estimatedStripeFee = roundToUnit(
                BigDecimal.valueOf(chargeAmount).multiply(BigDecimal.valueOf(stripeFeeRate)));
        long estimatedNetProfit = applicationFeeAmount - estimatedStripeFee;

        // 不変条件: 総手数料は請求額を超えない（escrow_transactions.chk_et_fee と整合）。
        // 0 < totalFeeRate(0.05) < 1 かつ chargeAmount = face × (1 + payerFeeRate) であるため
        // 構造的に成立するが、係数変更時の安全網として防御的に検査する。
        if (applicationFeeAmount > chargeAmount) {
            throw new IllegalStateException(
                    "applicationFeeAmount(" + applicationFeeAmount + ") must not exceed chargeAmount(" + chargeAmount + ")");
        }

        return new FeeBreakdown(
                faceAmount,
                payerFee,
                chargeAmount,
                applicationFeeAmount,
                transferAmount,
                estimatedStripeFee,
                estimatedNetProfit);
    }

    /**
     * 額面と手数料パターン（{@link FeePolicy}・率%＋固定額¥）から手数料内訳を計算する（R1・手数料ランク化）。
     *
     * <p>既定の Stripe 手数料率 {@value #DEFAULT_STRIPE_FEE_RATE} を用いる。計算規約（設計書 02 §3.5・折半50/50固定）:</p>
     * <pre>
     * total_fee             = round(policy.percentRate × faceAmount) + policy.flatFeeMinor   // 総手数料
     * half_fee              = round(total_fee ÷ 2)                                            // 折半
     * payerFee              = half_fee                                                        // 支払者上乗せ
     * chargeAmount          = faceAmount + half_fee                                           // 実請求額（amount）
     * applicationFeeAmount  = total_fee                                                       // Mannschaft 徴収
     * transferAmount        = chargeAmount − total_fee                                        // 受取側送金額
     * </pre>
     *
     * <p><b>安全ガード（必須・設計書 02 §3.5.2）:</b> {@code total_fee > faceAmount} のとき
     * 「払った額より手数料が高い」破綻（および Stripe {@code application_fee_amount ≤ amount} 違反）を招くため、
     * {@link IllegalArgumentException} で拒否する（純粋関数の防御。Service 層では
     * {@code ConnectPaymentErrorCode.FEE_EXCEEDS_FACE_AMOUNT}＝422 へ変換し症状を隠さない）。</p>
     *
     * <p><b>DEFAULT policy（率5%＋固定0）では総手数料 = round(0.05 × face)・折半 = round(total/2) となり、
     * 額面 10,000 で total=500 / payerFee=250 / charge=10,250 / appFee=500 / transfer=9,750</b>（後方互換・§3.5.4）。</p>
     *
     * @param faceAmount 額面（円整数・最小単位・正値）
     * @param policy     解決済み手数料パターン（{@link FeePolicyResolver} で解決・非 null）
     * @return 手数料内訳
     * @throws IllegalArgumentException faceAmount が 0 以下、policy が null、または安全ガード違反（total_fee > face）のとき
     */
    public FeeBreakdown calculate(long faceAmount, FeePolicy policy) {
        return calculate(faceAmount, policy, DEFAULT_STRIPE_FEE_RATE);
    }

    /**
     * 額面・手数料パターン・Stripe 手数料率から手数料内訳を計算する（R1・手数料ランク化）。
     *
     * @param faceAmount    額面（円整数・最小単位・正値）
     * @param policy        解決済み手数料パターン（非 null）
     * @param stripeFeeRate Stripe 実手数料率（0 以上 1 未満・参考純益試算用）
     * @return 手数料内訳
     * @throws IllegalArgumentException faceAmount が 0 以下、policy が null、stripeFeeRate が範囲外、
     *                                  または安全ガード違反（total_fee > face）のとき
     */
    public FeeBreakdown calculate(long faceAmount, FeePolicy policy, double stripeFeeRate) {
        if (faceAmount <= 0L) {
            throw new IllegalArgumentException("faceAmount must be positive (円整数): " + faceAmount);
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (stripeFeeRate < 0d || stripeFeeRate >= 1d) {
            throw new IllegalArgumentException("stripeFeeRate must be in [0, 1): " + stripeFeeRate);
        }

        BigDecimal face = BigDecimal.valueOf(faceAmount);

        // 総手数料 = round(率 × 額面) + 固定額（円整数・HALF_UP・double 誤差なし）。
        long totalFee = roundToUnit(face.multiply(policy.percentRate())) + policy.flatFeeMinor();

        // 安全ガード（必須・§3.5.2）: 総手数料が額面を超えると破綻するため拒否する（症状を隠さない・純粋関数の防御）。
        if (totalFee > faceAmount) {
            throw new IllegalArgumentException(
                    "total fee(" + totalFee + ") must not exceed faceAmount(" + faceAmount
                            + ") [policyKey=" + policy.policyKey() + "]");
        }

        // 折半（50/50 固定）: half_fee = round(total_fee / 2)。支払者上乗せ = half_fee。
        long halfFee = roundToUnit(BigDecimal.valueOf(totalFee).divide(BigDecimal.valueOf(2L)));
        long payerFee = halfFee;
        long chargeAmount = faceAmount + halfFee;
        long applicationFeeAmount = totalFee;
        long transferAmount = chargeAmount - applicationFeeAmount;

        long estimatedStripeFee = roundToUnit(
                BigDecimal.valueOf(chargeAmount).multiply(BigDecimal.valueOf(stripeFeeRate)));
        long estimatedNetProfit = applicationFeeAmount - estimatedStripeFee;

        // 不変条件（chk_et_fee と整合）: 安全ガード（total_fee ≤ face）かつ half_fee ≥ 0 ゆえ常に成立する防御網。
        if (applicationFeeAmount > chargeAmount) {
            throw new IllegalStateException(
                    "applicationFeeAmount(" + applicationFeeAmount + ") must not exceed chargeAmount(" + chargeAmount + ")");
        }

        return new FeeBreakdown(
                faceAmount,
                payerFee,
                chargeAmount,
                applicationFeeAmount,
                transferAmount,
                estimatedStripeFee,
                estimatedNetProfit);
    }

    /**
     * 円（ゼロデシマル最小単位）へ四捨五入（HALF_UP）して切り詰める。Math.round 相当だが double 誤差を持たない。
     */
    private static long roundToUnit(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
