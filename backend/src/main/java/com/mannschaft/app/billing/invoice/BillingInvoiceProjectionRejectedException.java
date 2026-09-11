package com.mannschaft.app.billing.invoice;

/**
 * F20.1 PR5: invoice 投影を<b>恒久的に</b>拒否したことを表す例外（fail-closed）。
 *
 * <p>「再送すれば直る一時失敗」ではなく「その検体自体が投影してはならないもの」を表す。
 * 非 JPY・金額恒等式の破れ・負の金額・税率過大・税の裏付け不在などが該当する
 * （設計書 05 §8 / AC-5・AC-34・AC-39）。</p>
 *
 * <p><b>DB 制約に頼らない理由</b>: {@code billing_invoices} には {@code CHECK (currency='JPY')} があるため、
 * 素朴に INSERT しても DB 層で落ちて「投影されていない」状態にはなる。しかしそれは
 * <b>投影を試みてから落ちている</b>のであって fail-closed ではない。トランザクションを汚し、
 * 同一イベントで一体に成立させるべき契約遷移まで巻き添えにする。本例外は
 * <b>永続化を一切試みる前に</b>投げられ、その旨をログと例外型で観測可能にする。</p>
 */
public class BillingInvoiceProjectionRejectedException extends RuntimeException {

    public BillingInvoiceProjectionRejectedException(String message) {
        super(message);
    }
}
