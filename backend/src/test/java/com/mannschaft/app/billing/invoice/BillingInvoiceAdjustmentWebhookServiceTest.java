package com.mannschaft.app.billing.invoice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentEntity;
import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.payment.WebhookIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F20.1 PR5-B: 調整投影の契約テスト（AC-30 / 32 / 33）。
 *
 * <p><b>ここで測る核心</b>: dispute の対象請求書を <b>charge の {@code invoice} から一意に</b>解決すること。
 * 「その顧客の直近の請求書」といった推測で代用していないことを、解決器が空を返したときに
 * <b>1 行も投影せず、請求書の検索すら行わない</b>ことで確かめる。金銭ドメインで返金が別の請求書に
 * ぶら下がると利用者が見る金額が狂うため、近似は許されない。</p>
 *
 * <p>オフラインの IT では Stripe から charge を取得できないため、この経路の検証はここで担保する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F20.1 PR5: BillingInvoiceAdjustmentWebhookService")
class BillingInvoiceAdjustmentWebhookServiceTest {

    private static final String INVOICE_REF = "in_unit_adj";
    private static final String CHARGE_REF = "ch_unit_adj";

    @Mock private BillingInvoiceJpaRepository invoiceRepository;
    @Mock private BillingInvoiceAdjustmentJpaRepository adjustmentRepository;
    @Mock private StripeChargeInvoiceResolver chargeInvoiceResolver;
    @Mock private WebhookIdempotencyService idempotencyService;

    private BillingInvoiceAdjustmentWebhookService service;

    private final UUID invoiceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        StripeBillingPayloadParser parser = new StripeBillingPayloadParser(new ObjectMapper());
        BillingWebhookEventGate gate = new BillingWebhookEventGate(idempotencyService, parser);
        service = new BillingInvoiceAdjustmentWebhookService(
                parser, invoiceRepository, adjustmentRepository, chargeInvoiceResolver, gate);

        when(idempotencyService.tryBegin(anyString(), anyString(), anyBoolean(),
                any(), any(), any(), any())).thenReturn(true);
        when(adjustmentRepository.findByPspObjectRef(anyString())).thenReturn(Optional.empty());
    }

    private BillingInvoiceEntity invoice() {
        BillingInvoiceEntity e = BillingInvoiceEntity.builder()
                .pspInvoiceRef(INVOICE_REF).status("PAID").currency("JPY").build();
        e.setId(invoiceId);
        return e;
    }

    private String disputeEvent(String chargeJson) {
        return """
                {"id":"evt_dp","object":"event","created":1769904000,"livemode":false,
                 "type":"charge.dispute.created",
                 "data":{"object":{"id":"dp_unit","object":"dispute","charge":%s,"amount":10450,
                                   "currency":"jpy","status":"needs_response","reason":"fraudulent",
                                   "created":1769904000}}}"""
                .formatted(chargeJson);
    }

    @Test
    @DisplayName("AC-30: dispute の charge が展開されていれば、その charge.invoice の請求書へ投影する")
    void dispute_展開済みchargeのinvoiceへ投影する() {
        when(invoiceRepository.findByPspInvoiceRef(INVOICE_REF)).thenReturn(Optional.of(invoice()));

        String expandedCharge = "{\"id\":\"" + CHARGE_REF + "\",\"object\":\"charge\",\"invoice\":\""
                + INVOICE_REF + "\"}";
        boolean handled = service.handleAdjustmentEventIfBilling(disputeEvent(expandedCharge));

        assertThat(handled).isTrue();
        ArgumentCaptor<BillingInvoiceAdjustmentEntity> saved =
                ArgumentCaptor.forClass(BillingInvoiceAdjustmentEntity.class);
        verify(adjustmentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("DISPUTE");
        assertThat(saved.getValue().getPspObjectRef()).isEqualTo("dp_unit");
        assertThat(saved.getValue().getInvoiceId())
                .as("charge.invoice が指した請求書に紐づく").isEqualTo(invoiceId);
    }

    @Test
    @DisplayName("AC-30: charge が ID のみなら Stripe から charge を取得し、その invoice の請求書へ投影する")
    void dispute_charge取得経由でinvoiceへ投影する() {
        when(chargeInvoiceResolver.resolveInvoiceRef(CHARGE_REF)).thenReturn(Optional.of(INVOICE_REF));
        when(invoiceRepository.findByPspInvoiceRef(INVOICE_REF)).thenReturn(Optional.of(invoice()));

        boolean handled = service.handleAdjustmentEventIfBilling(disputeEvent("\"" + CHARGE_REF + "\""));

        assertThat(handled).isTrue();
        verify(chargeInvoiceResolver).resolveInvoiceRef(CHARGE_REF);
        verify(adjustmentRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("charge から invoice を特定できないときは推測で紐付けず 1 行も投影しない（fail-closed）")
    void dispute_invoiceを特定できなければ投影しない() {
        when(chargeInvoiceResolver.resolveInvoiceRef(CHARGE_REF)).thenReturn(Optional.empty());

        boolean handled = service.handleAdjustmentEventIfBilling(disputeEvent("\"" + CHARGE_REF + "\""));

        assertThat(handled).as("billing 所有と断定できないので消費しない").isFalse();
        verify(adjustmentRepository, never()).saveAndFlush(any());
        // 「顧客の直近の請求書」などで代用していないこと＝請求書の検索を一切していないこと。
        verify(invoiceRepository, never()).findByPspInvoiceRef(anyString());
    }

    @Test
    @DisplayName("AC-33: transfer_data を持つ Connect 由来の charge.refunded は投影しない")
    void refund_Connect由来は投影しない() {
        String payload = """
                {"id":"evt_re","object":"event","created":1769904000,"livemode":false,
                 "type":"charge.refunded",
                 "data":{"object":{"id":"%s","object":"charge","amount":10450,"amount_refunded":3000,
                                   "currency":"jpy","invoice":"%s",
                                   "transfer_data":{"destination":"acct_seller"},
                                   "refunds":{"object":"list","data":[
                                     {"id":"re_unit","object":"refund","amount":3000,"status":"succeeded",
                                      "created":1769904000}]}}}}"""
                .formatted(CHARGE_REF, INVOICE_REF);

        assertThat(service.handleAdjustmentEventIfBilling(payload)).isFalse();
        verify(adjustmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AC-32: charge に invoice が無い返金は投影しない（charge → invoice を辿れない）")
    void refund_invoiceを辿れなければ投影しない() {
        String payload = """
                {"id":"evt_re2","object":"event","created":1769904000,"livemode":false,
                 "type":"charge.refunded",
                 "data":{"object":{"id":"%s","object":"charge","amount":5000,"amount_refunded":5000,
                                   "currency":"jpy","invoice":null,
                                   "refunds":{"object":"list","data":[
                                     {"id":"re_unit2","object":"refund","amount":5000,"status":"succeeded",
                                      "created":1769904000}]}}}}"""
                .formatted(CHARGE_REF);

        assertThat(service.handleAdjustmentEventIfBilling(payload)).isFalse();
        verify(adjustmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("陽性対照: 正当な charge.refunded は REFUND 行として投影される（常に空で緑にしない）")
    void refund_正当な検体は投影される() {
        when(invoiceRepository.findByPspInvoiceRef(INVOICE_REF)).thenReturn(Optional.of(invoice()));
        String payload = """
                {"id":"evt_re3","object":"event","created":1769904000,"livemode":false,
                 "type":"charge.refunded",
                 "data":{"object":{"id":"%s","object":"charge","amount":10450,"amount_refunded":3000,
                                   "currency":"jpy","invoice":"%s",
                                   "refunds":{"object":"list","data":[
                                     {"id":"re_unit3","object":"refund","amount":3000,"status":"succeeded",
                                      "reason":"requested_by_customer","created":1769904000}]}}}}"""
                .formatted(CHARGE_REF, INVOICE_REF);

        assertThat(service.handleAdjustmentEventIfBilling(payload)).isTrue();
        ArgumentCaptor<BillingInvoiceAdjustmentEntity> saved =
                ArgumentCaptor.forClass(BillingInvoiceAdjustmentEntity.class);
        verify(adjustmentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("REFUND");
        assertThat(saved.getValue().getAmount()).isEqualTo(3_000L);
        assertThat(saved.getValue().getPspObjectRef()).isEqualTo("re_unit3");
    }
}
