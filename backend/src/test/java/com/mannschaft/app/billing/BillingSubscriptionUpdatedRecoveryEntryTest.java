package com.mannschaft.app.billing;

import com.mannschaft.app.billing.invoice.BillingInvoiceAdjustmentWebhookService;
import com.mannschaft.app.billing.invoice.BillingWebhookEventGate;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.EventEnvelope;
import com.mannschaft.app.billing.invoice.StripeBillingPayloadParser;
import com.mannschaft.app.notification.credit.service.NotificationCreditCheckoutService;
import com.mannschaft.app.payment.escrow.EscrowWebhookService;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.service.MembershipSubscriptionWebhookService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.service.StripeWebhookService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 試練D（第4b隊）: <b>AC-83</b> — {@code customer.subscription.updated} を billing が
 * <b>受け取る</b>ことを測る。
 *
 * <h2>現行の何が欠けているのか（実読で確認した事実）</h2>
 * <ul>
 *   <li>{@code StripeWebhookService} の {@code PR5_PENDING_EVENT_TYPES} に
 *       {@code customer.subscription.updated} が入っており、<b>billing へ渡る前に</b>
 *       「受信したが確定しない（RECEIVED）」として記録して return している。</li>
 *   <li>{@code MembershipSubscriptionWebhookService#isSubscriptionEvent} は
 *       {@code invoice.} 接頭辞と {@code customer.subscription.deleted} だけを対象としており、
 *       {@code updated} はそもそも subscription イベントの委譲分岐に入らない。</li>
 *   <li>{@code BillingSubscriptionWebhookService#handleSubscriptionEventIfBilling} の
 *       {@code switch} も {@code deleted} だけを処理し、他は IGNORED である。</li>
 * </ul>
 *
 * <h2>PR6a と PR6b の線引き（重要）</h2>
 * <p>PR6a で {@code updated} を受けるのは<b>停止窓の回収の入口としてだけ</b>である
 * （{@link BillingContractOperationRecoveryService#RECOVERY_ENTRY_EVENT_TYPE}）。
 * <b>プラン変更（items 差し替え・{@code pending_update}）の {@code APPLIED} 判定は PR6b の担当</b>であり、
 * 本 PR では実装しない。したがって本テストは「billing の受け口へ渡ること」と
 * 「渡ったイベントが取りこぼされないこと」だけを固定し、プラン変更の反映は一切測らない。</p>
 *
 * <p>回収そのもの（stale 走査・状態遷移）は {@code BillingContractOperationRecoveryIT} が測る。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("試練D: AC-83 customer.subscription.updated を billing が受け取る（回収の入口）")
class BillingSubscriptionUpdatedRecoveryEntryTest {

    private static final String PAYLOAD = "payload-subscription-updated";
    private static final String SIG = "sig";
    private static final String EVENT_TYPE = "customer.subscription.updated";

    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private NotificationCreditCheckoutService notificationCreditCheckoutService;
    @Mock private EscrowWebhookService escrowWebhookService;
    @Mock private MembershipSubscriptionWebhookService membershipSubscriptionWebhookService;
    @Mock private BillingSubscriptionWebhookService billingSubscriptionWebhookService;
    @Mock private BillingInvoiceAdjustmentWebhookService billingInvoiceAdjustmentWebhookService;
    @Mock private BillingWebhookEventGate billingWebhookEventGate;
    @Mock private StripeBillingPayloadParser billingPayloadParser;

    @InjectMocks private StripeWebhookService dispatcher;

    @Test
    @DisplayName("AC-83: customer.subscription.updated は billing の subscription 受け口へ渡される（PR5 の保留リストで塞き止めない）")
    void updatedEventReachesBilling() {
        givenUpdatedEvent();
        given(billingSubscriptionWebhookService.handleSubscriptionEventIfBilling(PAYLOAD, SIG))
                .willReturn(true);

        dispatcher.handleWebhook(PAYLOAD, SIG);

        verify(billingSubscriptionWebhookService).handleSubscriptionEventIfBilling(PAYLOAD, SIG);
    }

    @Test
    @DisplayName("AC-83: billing が処理したイベントを保留（RECEIVED のまま記録）として二重に扱わない")
    void claimedEventIsNotAlsoRecordedAsPending() {
        givenUpdatedEvent();
        given(billingSubscriptionWebhookService.handleSubscriptionEventIfBilling(PAYLOAD, SIG))
                .willReturn(true);

        dispatcher.handleWebhook(PAYLOAD, SIG);

        verify(billingWebhookEventGate, never()).recordPending(any(), anyString(), any());
        verify(membershipSubscriptionWebhookService, never()).handleWebhook(anyString(), anyString());
    }

    @Test
    @DisplayName("AC-83: billing が所有しないと言った updated は従来どおり保留記録に落ち、取りこぼされない（陰性対照）")
    void unclaimedEventIsStillRecorded() {
        givenUpdatedEvent();
        given(billingSubscriptionWebhookService.handleSubscriptionEventIfBilling(PAYLOAD, SIG))
                .willReturn(false);
        given(billingPayloadParser.parseEnvelope(PAYLOAD))
                .willReturn(Optional.of(new EventEnvelope("evt_updated", EVENT_TYPE, false, 1_800_000_000L)));

        dispatcher.handleWebhook(PAYLOAD, SIG);

        verify(billingWebhookEventGate).recordPending(any(), eq(PAYLOAD), any());
    }

    @Test
    @DisplayName("AC-83: 回収の入口種別は customer.subscription.updated であり、プラン変更の APPLIED 判定は PR6b の担当（線引きの明示）")
    void recoveryEntryEventTypeIsDeclared() {
        assertThat(BillingContractOperationRecoveryService.RECOVERY_ENTRY_EVENT_TYPE)
                .isEqualTo(EVENT_TYPE);
        assertThat(BillingContractOperationRecoveryService.isRecoveryEntryEvent(EVENT_TYPE)).isTrue();
        assertThat(BillingContractOperationRecoveryService
                .isRecoveryEntryEvent("customer.subscription.pending_update_applied"))
                .as("pending_update 系は PR6b の担当であり PR6a の回収入口ではない")
                .isFalse();
    }

    private void givenUpdatedEvent() {
        given(stripePaymentProvider.constructEvent(PAYLOAD, SIG))
                .willReturn(new StripePaymentProvider.WebhookEventInfo(
                        EVENT_TYPE, null, null, null, "sub_updated",
                        null, null, null, null, null, null));
    }
}
