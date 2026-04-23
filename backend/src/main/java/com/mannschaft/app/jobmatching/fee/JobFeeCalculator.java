package com.mannschaft.app.jobmatching.fee;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * F13.1 求人マッチング 手数料計算器。
 *
 * <p>設計書 §3「手数料設計」に基づいて、業務報酬（base_reward）から
 * Requester 支払総額および Worker 受取額の内訳（{@link FeeBreakdown}）を算出する。</p>
 *
 * <h3>計算式（整数円・ROUND_HALF_UP）</h3>
 * <ul>
 *   <li>{@code requester_fee = round(base_reward × 10%) + 100}</li>
 *   <li>{@code requester_fee_tax = round(requester_fee × 10%)}（消費税）</li>
 *   <li>{@code requester_total = base_reward + requester_fee + requester_fee_tax}</li>
 *   <li>{@code worker_fee = round(base_reward × 2%) + 100}</li>
 *   <li>{@code worker_receipt = base_reward - worker_fee}</li>
 * </ul>
 *
 * <p>Stripe 決済手数料は Mannschaft の粗利から控除される設計のため、本計算には含めない。
 * 消費税は Requester 手数料部分にのみ加算する（業務報酬・Worker 手数料には課税しない）。</p>
 *
 * <h3>計算例（設計書 §3.3 と一致）</h3>
 * <pre>
 *   base_reward = 3,000円
 *     → Requester 支払総額: 3,440円（手数料 400円 + 消費税 40円）
 *     → Worker 受取額: 2,840円（手数料 160円）
 * </pre>
 */
@Component
public class JobFeeCalculator {

    /** Requester 手数料率（業務報酬の 10%）。 */
    private static final BigDecimal REQUESTER_FEE_RATE = new BigDecimal("0.10");

    /** Requester 手数料の固定額（円）。 */
    private static final int REQUESTER_FEE_FIXED = 100;

    /** Worker 手数料率（業務報酬の 2%）。 */
    private static final BigDecimal WORKER_FEE_RATE = new BigDecimal("0.02");

    /** Worker 手数料の固定額（円）。 */
    private static final int WORKER_FEE_FIXED = 100;

    /** 消費税率（10%）。Requester 手数料にのみ適用する。 */
    private static final BigDecimal TAX_RATE = new BigDecimal("0.10");

    /**
     * 業務報酬額を元に手数料内訳を算出する。
     *
     * @param baseRewardJpy 業務報酬（整数円）。負値不可。
     * @return 手数料内訳
     * @throws IllegalArgumentException 業務報酬が負値の場合
     */
    public FeeBreakdown calculate(int baseRewardJpy) {
        if (baseRewardJpy < 0) {
            throw new IllegalArgumentException("業務報酬は 0 円以上でなければなりません: " + baseRewardJpy);
        }

        BigDecimal base = BigDecimal.valueOf(baseRewardJpy);

        // Requester 手数料 = round(base × 10%) + 100円
        int requesterFeePercent = base.multiply(REQUESTER_FEE_RATE)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        int requesterFee = requesterFeePercent + REQUESTER_FEE_FIXED;

        // Requester 手数料消費税 = round(requester_fee × 10%)
        int requesterFeeTax = BigDecimal.valueOf(requesterFee)
                .multiply(TAX_RATE)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();

        // Requester 支払総額（税込）= base + requester_fee + requester_fee_tax
        int requesterTotal = baseRewardJpy + requesterFee + requesterFeeTax;

        // Worker 手数料 = round(base × 2%) + 100円
        int workerFeePercent = base.multiply(WORKER_FEE_RATE)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        int workerFee = workerFeePercent + WORKER_FEE_FIXED;

        // Worker 受取額 = base - worker_fee
        int workerReceipt = baseRewardJpy - workerFee;

        return new FeeBreakdown(
                baseRewardJpy,
                requesterFee,
                requesterFeeTax,
                requesterTotal,
                workerFee,
                workerReceipt
        );
    }
}
