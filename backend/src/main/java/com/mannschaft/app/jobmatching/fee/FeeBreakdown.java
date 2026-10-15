package com.mannschaft.app.jobmatching.fee;

/**
 * 求人マッチングの手数料内訳を表す値オブジェクト。
 *
 * <p>F13.1 §3 の手数料計算結果を保持する。すべての額は整数円（JPY）。</p>
 *
 * <ul>
 *   <li>{@code baseRewardJpy} — 業務報酬（Requester が設定した基本額）</li>
 *   <li>{@code requesterFeeJpy} — Requester 手数料（業務報酬の 10% + 100 円）</li>
 *   <li>{@code requesterFeeTaxJpy} — Requester 手数料に対する消費税 10%</li>
 *   <li>{@code requesterTotalJpy} — Requester が実際に決済で支払う総額（税込）</li>
 *   <li>{@code workerFeeJpy} — Worker 手数料（業務報酬の 2% + 100 円）</li>
 *   <li>{@code workerReceiptJpy} — Worker が受け取る額（業務報酬 − Worker 手数料）</li>
 * </ul>
 *
 * <p>Stripe 決済手数料は Mannschaft の粗利から控除される設計のため、この内訳には含まない。</p>
 */
public record FeeBreakdown(
        int baseRewardJpy,
        int requesterFeeJpy,
        int requesterFeeTaxJpy,
        int requesterTotalJpy,
        int workerFeeJpy,
        int workerReceiptJpy
) {
}
