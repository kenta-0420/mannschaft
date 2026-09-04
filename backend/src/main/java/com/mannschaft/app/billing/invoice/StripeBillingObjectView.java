package com.mannschaft.app.billing.invoice;

import java.math.BigDecimal;
import java.util.List;

/**
 * F20.1 PR5: Stripe webhook の {@code data.object} を投影に必要な形へ写した読み取り専用ビュー群。
 *
 * <p><b>なぜ独自 view なのか</b>: 投影は Stripe SDK のモデルをそのまま持ち回るのではなく、
 * 「投影に必要な項目だけ」を確定した型で受け渡す。これにより
 * {@link com.mannschaft.app.payment.stripe.StripePaymentProvider} の record を PR5 都合で
 * 肥大化させずに済み、payment ドメインへの逆流も起こさない（モジュラーモノリスの境界維持）。</p>
 */
public final class StripeBillingObjectView {

    private StripeBillingObjectView() {
    }

    /**
     * Stripe Event 封筒。
     *
     * @param eventId          {@code evt_xxx}
     * @param type             イベント種別
     * @param livemode         本番/テスト区分
     * @param createdEpochSec  {@code event.created}（単調更新の判定に使う）
     */
    public record EventEnvelope(String eventId, String type, boolean livemode, long createdEpochSec) {
    }

    /** Stripe Invoice。金額はすべて最小通貨単位（JPY は円）。 */
    public record InvoiceView(
            String id,
            String customerRef,
            String subscriptionRef,
            String status,
            String billingReason,
            String currency,
            long subtotal,
            long discount,
            long tax,
            long total,
            Long periodStartEpochSec,
            Long periodEndEpochSec,
            String customerName,
            String customerEmail,
            String customerAddressJson,
            List<InvoiceLineView> lines) {
    }

    /** Stripe Invoice の明細行。 */
    public record InvoiceLineView(
            String id,
            String description,
            BigDecimal quantity,
            long amount,
            long discountAmount,
            long taxAmount,
            boolean taxInclusive,
            Integer taxRateBasisPoints,
            String taxName,
            String priceRef,
            Long periodStartEpochSec,
            Long periodEndEpochSec) {
    }

    /** Stripe Charge（{@code charge.refunded}）。 */
    public record ChargeView(
            String id,
            String invoiceRef,
            long amount,
            long amountRefunded,
            boolean connectOwned,
            List<RefundView> refunds) {
    }

    /** Stripe Refund。 */
    public record RefundView(String id, long amount, String status, String reason, Long createdEpochSec) {
    }

    /** Stripe CreditNote。 */
    public record CreditNoteView(
            String id, String invoiceRef, long amount, String status, String reason, Long createdEpochSec) {
    }

    /** Stripe Dispute。 */
    public record DisputeView(
            String id, String chargeRef, long amount, String status, String reason, Long createdEpochSec) {
    }
}
