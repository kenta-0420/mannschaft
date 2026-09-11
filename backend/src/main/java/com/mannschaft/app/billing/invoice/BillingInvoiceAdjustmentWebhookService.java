package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentEntity;
import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.ChargeView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.CreditNoteView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.DisputeView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.EventEnvelope;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.RefundView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * F20.1 PR5-B: 返金 / credit note / dispute を {@code billing_invoice_adjustments} へ投影する。
 *
 * <p><b>invoice lifecycle と混ぜない</b>: これらは {@code billing_invoices.status} を書き換えない。
 * 「請求書はこの内容で発行された」という事実と、「そのあと返金された」という事実は別の行として
 * 不変に積む（設計書 05 §4・AC-28）。</p>
 *
 * <p><b>Connect 由来は投影しない</b>: {@code transfer_data} / {@code on_behalf_of} を持つ charge は
 * connected account 宛の売上であり、プラットフォーム受取（billing）の請求書に対する調整ではない（AC-33）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingInvoiceAdjustmentWebhookService {

    private static final String KIND_REFUND = "REFUND";
    private static final String KIND_CREDIT_NOTE = "CREDIT_NOTE";
    private static final String KIND_DISPUTE = "DISPUTE";

    private final StripeBillingPayloadParser parser;
    private final BillingInvoiceJpaRepository invoiceRepository;
    private final BillingInvoiceAdjustmentJpaRepository adjustmentRepository;
    /** dispute の対象請求書を charge の invoice から一意に解決する（推測しない）。 */
    private final StripeChargeInvoiceResolver chargeInvoiceResolver;
    private final BillingWebhookEventGate gate;

    /**
     * 調整系イベントを処理する。
     *
     * @param payload 署名検証済みの生 payload
     * @return billing が処理したら {@code true}（呼び出し側はフォールバックしない）
     */
    public boolean handleAdjustmentEventIfBilling(String payload) {
        Optional<EventEnvelope> envelope = parser.parseEnvelope(payload);
        if (envelope.isEmpty() || envelope.get().type() == null) {
            return false;
        }
        EventEnvelope env = envelope.get();
        return switch (env.type()) {
            case "charge.refunded" -> handleRefund(payload, env);
            case "credit_note.created", "credit_note.updated", "credit_note.voided" ->
                    handleCreditNote(payload, env);
            case "charge.dispute.created", "charge.dispute.updated", "charge.dispute.closed",
                 "charge.dispute.funds_withdrawn", "charge.dispute.funds_reinstated" ->
                    handleDispute(payload, env);
            default -> false;
        };
    }

    // ───────────── charge.refunded ─────────────

    private boolean handleRefund(String payload, EventEnvelope env) {
        Optional<ChargeView> parsed = parser.parseCharge(payload);
        if (parsed.isEmpty()) {
            return false;
        }
        ChargeView charge = parsed.get();
        if (charge.connectOwned()) {
            log.info("F20.1 PR5: Connect 由来の charge のため billing の adjustments には投影しない: charge={}",
                    charge.id());
            return false;
        }
        if (charge.invoiceRef() == null) {
            // charge → invoice を辿れない返金は、当該 scope の請求書に対する調整だと断定できない（AC-32）。
            log.info("F20.1 PR5: invoice を辿れない charge のため投影しない: charge={}", charge.id());
            return false;
        }
        Optional<BillingInvoiceEntity> invoice = invoiceRepository.findByPspInvoiceRef(charge.invoiceRef());
        if (invoice.isEmpty()) {
            // billing の投影に無い invoice の返金＝billing 所有ではない（会費/謝礼側へフォールバック）。
            return false;
        }
        BillingInvoiceEntity target = invoice.get();

        // stripe_object_ref には charge id を残す（監査で「どの charge のイベントか」を辿れるように）。
        return gate.run(env, payload, charge.id(), target.getContractId(), target.getBillingCustomerId(), () -> {
            for (RefundView refund : charge.refunds()) {
                upsert(target, KIND_REFUND, refund.id(), refund.amount(),
                        mapRefundStatus(refund.status()), refund.reason(), refund.createdEpochSec());
            }
        });
    }

    private String mapRefundStatus(String stripeStatus) {
        return switch (stripeStatus == null ? "" : stripeStatus) {
            case "succeeded" -> "SUCCEEDED";
            case "failed", "canceled" -> "FAILED";
            default -> "PENDING";
        };
    }

    // ───────────── credit_note.* ─────────────

    private boolean handleCreditNote(String payload, EventEnvelope env) {
        Optional<CreditNoteView> parsed = parser.parseCreditNote(payload);
        if (parsed.isEmpty() || parsed.get().invoiceRef() == null) {
            return false;
        }
        CreditNoteView note = parsed.get();
        Optional<BillingInvoiceEntity> invoice = invoiceRepository.findByPspInvoiceRef(note.invoiceRef());
        if (invoice.isEmpty()) {
            return false;
        }
        BillingInvoiceEntity target = invoice.get();
        return gate.run(env, payload, note.id(), target.getContractId(), target.getBillingCustomerId(),
                () -> upsert(target, KIND_CREDIT_NOTE, note.id(), note.amount(),
                        mapCreditNoteStatus(note.status()), note.reason(), note.createdEpochSec()));
    }

    private String mapCreditNoteStatus(String stripeStatus) {
        return switch (stripeStatus == null ? "" : stripeStatus) {
            case "issued" -> "SUCCEEDED";
            case "void" -> "CLOSED";
            default -> "PENDING";
        };
    }

    // ───────────── charge.dispute.* ─────────────

    private boolean handleDispute(String payload, EventEnvelope env) {
        Optional<DisputeView> parsed = parser.parseDispute(payload);
        if (parsed.isEmpty() || parsed.get().chargeRef() == null) {
            return false;
        }
        DisputeView dispute = parsed.get();
        Optional<BillingInvoiceEntity> invoice = resolveInvoiceForDispute(dispute);
        if (invoice.isEmpty()) {
            return false;
        }
        BillingInvoiceEntity target = invoice.get();
        return gate.run(env, payload, dispute.id(), target.getContractId(), target.getBillingCustomerId(),
                () -> upsert(target, KIND_DISPUTE, dispute.id(), dispute.amount(),
                        mapDisputeStatus(dispute.status()), dispute.reason(), dispute.createdEpochSec()));
    }

    /**
     * dispute の {@code charge} から対象 invoice を<b>一意に</b>解決する。
     *
     * <p>Stripe の Dispute は {@code invoice} を持たないが、{@code Charge} は持つ。したがって
     * 「Dispute → charge → charge.invoice」で一意に辿れる。payload 内で charge が展開されていれば
     * それを使い、されていなければ Stripe から charge を取得する。</p>
     *
     * <p><b>推測しない（fail-closed）</b>: ここで解決できないときに「その顧客の直近の請求書」で
     * 代用してはならない。返金・チャージバックが別の請求書にぶら下がると、利用者が見る金額が狂う。
     * 解決できない場合は投影しない。</p>
     */
    private Optional<BillingInvoiceEntity> resolveInvoiceForDispute(DisputeView dispute) {
        Optional<String> invoiceRef = dispute.expandedChargeInvoiceRef() != null
                ? Optional.of(dispute.expandedChargeInvoiceRef())
                : chargeInvoiceResolver.resolveInvoiceRef(dispute.chargeRef());
        if (invoiceRef.isEmpty()) {
            log.info("F20.1 PR5: dispute の対象請求書を charge から特定できないため投影しません（推測しない）: "
                    + "dispute={}, charge={}", dispute.id(), dispute.chargeRef());
            return Optional.empty();
        }
        return invoiceRepository.findByPspInvoiceRef(invoiceRef.get());
    }

    private String mapDisputeStatus(String stripeStatus) {
        return switch (stripeStatus == null ? "" : stripeStatus) {
            case "won", "warning_closed" -> stripeStatus.equals("won") ? "WON" : "CLOSED";
            case "lost" -> "LOST";
            case "charge_refunded" -> "CLOSED";
            default -> "OPEN";
        };
    }

    // ───────────── 共通 ─────────────

    /** {@code psp_object_ref} UNIQUE で冪等に不変行を積む（AC-31）。既存があれば書き換えない。 */
    private void upsert(BillingInvoiceEntity invoice, String kind, String objectRef, long amount,
                        String status, String reason, Long effectiveEpochSec) {
        if (objectRef == null) {
            log.warn("F20.1 PR5: psp_object_ref が無い調整は投影しない: invoice={}, kind={}",
                    invoice.getPspInvoiceRef(), kind);
            return;
        }
        if (adjustmentRepository.findByPspObjectRef(objectRef).isPresent()) {
            return;
        }
        Instant now = Instant.now();
        adjustmentRepository.saveAndFlush(BillingInvoiceAdjustmentEntity.builder()
                .invoiceId(invoice.getId())
                .organizationId(invoice.getOrganizationId())
                .kind(kind)
                .pspObjectRef(objectRef)
                .amount(Math.max(0L, amount))
                .currency("JPY")
                .status(status)
                .reason(truncate(reason))
                .effectiveAt(effectiveEpochSec == null ? now : Instant.ofEpochSecond(effectiveEpochSec))
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 128 ? reason : reason.substring(0, 128);
    }
}
