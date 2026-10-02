package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingTaxBehavior;

/**
 * 価格改定 create リクエストの band 入力（決定4）。
 *
 * <p>クライアントは Stripe Price ref や計算済み税額を渡せない（AC-44）。
 * 税額導出はサーバー側で {@code BillingTaxDerivationService} が行う。</p>
 *
 * @param bandNo       band 番号（1始まり連番）
 * @param minMembers   人数レンジ下限（1以上）
 * @param maxMembers   人数レンジ上限（null は open-ended。最終bandのみ許容）
 * @param inputAmount  金額入力（taxBehavior に応じ税抜/税込のいずれか。1〜9,999,999）
 * @param taxBehavior  税表示方式
 * @param taxCode      税コード（{@code billing_tax_codes.code}）
 */
public record PriceBandInput(
        int bandNo, int minMembers, Integer maxMembers, long inputAmount,
        BillingTaxBehavior taxBehavior, String taxCode) {
}
