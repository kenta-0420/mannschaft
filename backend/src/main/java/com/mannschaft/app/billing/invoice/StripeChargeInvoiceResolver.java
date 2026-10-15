package com.mannschaft.app.billing.invoice;

import java.util.Optional;

/**
 * F20.1 PR5: Stripe の charge から、その charge が属する invoice の参照を得る。
 *
 * <p><b>なぜ必要か</b>: {@code charge.dispute.*} の {@code data.object}（Dispute）は
 * {@code charge} は持つが {@code invoice} を持たない。一方 {@code Charge} は {@code invoice} を持つ。
 * したがって dispute の対象請求書は「Dispute → charge → charge.invoice」で<b>一意に</b>辿れる。</p>
 *
 * <p><b>推測してはならない</b>: ここが解決できないときに「その顧客の直近の請求書」などで代用すると、
 * 返金・チャージバックが<b>別の請求書にぶら下がって利用者が見る金額が狂う</b>。
 * 解決できない場合は {@link Optional#empty()} を返し、呼び出し元は投影を拒否する（fail-closed）。</p>
 */
public interface StripeChargeInvoiceResolver {

    /**
     * charge が属する invoice の参照（{@code in_xxx}）を返す。
     *
     * @param chargeRef {@code ch_xxx}
     * @return invoice 参照。charge に invoice が無い／取得できない場合は {@link Optional#empty()}
     */
    Optional<String> resolveInvoiceRef(String chargeRef);
}
