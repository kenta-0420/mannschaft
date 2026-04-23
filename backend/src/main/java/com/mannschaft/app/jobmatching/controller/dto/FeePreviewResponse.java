package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.fee.FeeBreakdown;

/**
 * 手数料プレビューレスポンス。
 *
 * <p>F13.1 §3 の手数料内訳をフロントエンド UI が表示するための DTO。
 * 内部計算結果 {@link FeeBreakdown} からファクトリメソッド {@link #from(FeeBreakdown)} 経由で生成する。</p>
 *
 * <p>各フィールドの意味は次の通り:</p>
 * <ul>
 *   <li>{@code baseRewardJpy} — 業務報酬（基本）</li>
 *   <li>{@code requesterFeeJpy} — Requester 手数料（業務報酬の 10% + 100 円）</li>
 *   <li>{@code requesterFeeTaxJpy} — Requester 手数料に対する消費税（10%）</li>
 *   <li>{@code requesterTotalJpy} — Requester 支払総額（税込）</li>
 *   <li>{@code workerFeeJpy} — Worker 手数料（業務報酬の 2% + 100 円）</li>
 *   <li>{@code workerReceiptJpy} — Worker 受取額</li>
 * </ul>
 */
public record FeePreviewResponse(
        int baseRewardJpy,
        int requesterFeeJpy,
        int requesterFeeTaxJpy,
        int requesterTotalJpy,
        int workerFeeJpy,
        int workerReceiptJpy
) {

    /**
     * {@link FeeBreakdown} から API レスポンス DTO を生成する。
     */
    public static FeePreviewResponse from(FeeBreakdown breakdown) {
        return new FeePreviewResponse(
                breakdown.baseRewardJpy(),
                breakdown.requesterFeeJpy(),
                breakdown.requesterFeeTaxJpy(),
                breakdown.requesterTotalJpy(),
                breakdown.workerFeeJpy(),
                breakdown.workerReceiptJpy()
        );
    }
}
