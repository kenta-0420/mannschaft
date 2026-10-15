package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceView;

import java.util.Optional;

/**
 * F20.1 PR5: Stripe から invoice を<b>全明細つき</b>で取得する。
 *
 * <p><b>なぜ必要か</b>: webhook の {@code data.object} に載る {@code lines.data} は件数上限で切られ、
 * そのとき {@code lines.has_more=true} になる。一方 {@code subtotal/tax/total} は<b>請求書全体の値</b>の
 * ままなので、切られた明細だけで {@link BillingInvoiceProjectionService#validate} の
 * 「line の税込合計 == total」を通ることはない。つまり明細が上限を超える請求書は、
 * webhook の payload だけでは<b>一度も投影できない</b>。全件を取りに行く経路がその唯一の解である。</p>
 *
 * <p><b>同着の裁定にも使う</b>: {@code event.created} は秒精度なので、同一秒に届いた同一状態の
 * イベントは時刻でも状態順序でも先後を決められない。そのときは payload を信じず、
 * ここで取得した「現在の Stripe 上の invoice」を正とする。</p>
 *
 * <p><b>推測してはならない</b>: 取得できないときに部分 payload で代用すると、明細が欠けた請求書が
 * 確定してしまう。解決できない場合は {@link Optional#empty()} を返し、呼び出し元は
 * 投影を見送る（fail-closed。Stripe の再送で再試行される）。</p>
 */
public interface StripeInvoiceRetriever {

    /**
     * invoice を取得する（明細は全ページを辿って揃える）。
     *
     * @param invoiceRef {@code in_xxx}
     * @return 全明細つきの invoice。取得できない場合は {@link Optional#empty()}
     */
    Optional<InvoiceView> retrieve(String invoiceRef);
}
