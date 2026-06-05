package com.mannschaft.app.payment;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.payment.escrow.LedgerDirection;
import com.mannschaft.app.payment.escrow.LedgerEntryEntity;
import com.mannschaft.app.payment.escrow.LedgerEntryRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.service.MembershipSubscriptionWebhookService;
import com.mannschaft.app.payment.service.StripeWebhookRetryableException;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P5 第三波: {@link MembershipSubscriptionWebhookService}（継続課金 platform Webhook）単体テスト。
 *
 * <p>invoice.created 固定手数料上書き（核心）・invoice.paid 記帳/状態反映・payment_failed/subscription.deleted の
 * 状態遷移・event_id 冪等を検証する。{@link PaymentFeeCalculator} は純粋関数のため実物を用い fee 算出を厳密検証する。
 * Stripe 実通信は {@link StripePaymentProvider} モックで遮断する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MembershipSubscriptionWebhookService 単体テスト（継続課金 Webhook）")
class MembershipSubscriptionWebhookServiceTest {

    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private WebhookIdempotencyService idempotencyService;
    @Mock private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Mock private MembershipSubscriptionService membershipSubscriptionService;
    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private FeePolicyRepository feePolicyRepository;

    /** 純粋関数なので実物を使う（fee 算出の厳密検証）。 */
    private final PaymentFeeCalculator paymentFeeCalculator = new PaymentFeeCalculator();

    private MembershipSubscriptionWebhookService service;

    private static final java.util.UUID SUB_ID = java.util.UUID.fromString("019607a0-0000-7000-8000-0000000000b1");

    @BeforeEach
    void setUp() {
        service = new MembershipSubscriptionWebhookService(
                stripePaymentProvider, idempotencyService, membershipSubscriptionRepository,
                membershipSubscriptionService, escrowTransactionRepository, ledgerEntryRepository,
                memberPaymentRepository, feePolicyRepository, paymentFeeCalculator);
    }

    private StripePaymentProvider.InvoiceWebhookEventInfo invoiceEvent(
            String eventId, String type, String subId, String invoiceId, String status, String billingReason) {
        return new StripePaymentProvider.InvoiceWebhookEventInfo(
                eventId, type, false, subId, invoiceId, status, billingReason,
                10_000L, "pi_cycle", "ch_cycle", 1_717_200_000L, 1_719_792_000L);
    }

    private MembershipSubscriptionEntity subscription(MembershipSubscriptionStatus status) {
        MembershipSubscriptionEntity s = MembershipSubscriptionEntity.builder()
                .organizationId(10L)
                .paymentItemId(55L)
                .beneficiaryUserId(200L)
                .payerUserId(100L)
                .scopeKind(ScopeKind.TEAM)
                .scopeId(7L)
                .payeeConnectAccountId(java.util.UUID.randomUUID())
                .stripeCustomerId("cus_x")
                .stripeSubscriptionId("sub_x")
                .billingInterval(BillingInterval.MONTHLY)
                .status(status)
                .feePolicyKey("DEFAULT")
                .faceAmount(10_000)
                .currency("JPY")
                .cancelAtPeriodEnd(false)
                .build();
        s.setId(SUB_ID);
        return s;
    }

    @Nested
    @DisplayName("invoice.created（固定手数料上書き・核心）")
    class InvoiceCreated {

        @Test
        @DisplayName("subscription_cycle×draft → fee_policy 算出値で application_fee_amount を上書き（DEFAULT 5%＝500）")
        void overridesApplicationFee() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_c1", "invoice.created", "sub_x", "in_1", "draft", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_c1"), any(), anyBoolean())).willReturn(true);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdAndDeletedAtIsNull("sub_x"))
                    .willReturn(Optional.of(subscription(MembershipSubscriptionStatus.ACTIVE)));
            given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DEFAULT")).willReturn(Optional.empty());

            service.handleWebhook("payload", "sig");

            ArgumentCaptor<Long> feeCaptor = ArgumentCaptor.forClass(Long.class);
            verify(stripePaymentProvider).updateInvoiceApplicationFee(eq("in_1"), feeCaptor.capture(), anyString());
            // DEFAULT（率5%＋固定0）・額面 10,000 → total_fee = round(0.05×10000) = 500。
            assertThat(feeCaptor.getValue()).isEqualTo(500L);
            verify(idempotencyService).markProcessed(eq("evt_c1"), eq(WebhookProcessStatus.PROCESSED));
        }

        @Test
        @DisplayName("焼き付け fee_policy_key（率3%＋固定50）で算出＝round(0.03×10000)+50=350 を上書き")
        void overridesWithBakedPolicy() {
            MembershipSubscriptionEntity sub = subscription(MembershipSubscriptionStatus.ACTIVE);
            sub = sub.toBuilder().feePolicyKey("ASSOC_BILLING").build();
            sub.setId(SUB_ID);
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_c2", "invoice.created", "sub_x", "in_2", "draft", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_c2"), any(), anyBoolean())).willReturn(true);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdAndDeletedAtIsNull("sub_x"))
                    .willReturn(Optional.of(sub));
            given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("ASSOC_BILLING")).willReturn(Optional.of(
                    FeePolicyEntity.builder().policyKey("ASSOC_BILLING").displayName("協会請求")
                            .percentRate(new BigDecimal("0.0300")).flatFeeMinor(50L).enabled(true).build()));

            service.handleWebhook("payload", "sig");

            ArgumentCaptor<Long> feeCaptor = ArgumentCaptor.forClass(Long.class);
            verify(stripePaymentProvider).updateInvoiceApplicationFee(eq("in_2"), feeCaptor.capture(), anyString());
            assertThat(feeCaptor.getValue()).isEqualTo(350L);
        }

        @Test
        @DisplayName("billing_reason=subscription_create（初回・案bでは発生しない）は上書きしない（防御・IGNORED）")
        void subscriptionCreate_noOverride() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_c3", "invoice.created", "sub_x", "in_3", "draft", "subscription_create"));
            given(idempotencyService.tryBegin(eq("evt_c3"), any(), anyBoolean())).willReturn(true);

            service.handleWebhook("payload", "sig");

            verify(stripePaymentProvider, never()).updateInvoiceApplicationFee(any(), anyLong(), any());
            verify(idempotencyService).markProcessed(eq("evt_c3"), eq(WebhookProcessStatus.IGNORED));
        }

        @Test
        @DisplayName("draft 以外（open）は窓外のため上書きしない（IGNORED）")
        void notDraft_noOverride() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_c4", "invoice.created", "sub_x", "in_4", "open", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_c4"), any(), anyBoolean())).willReturn(true);

            service.handleWebhook("payload", "sig");

            verify(stripePaymentProvider, never()).updateInvoiceApplicationFee(any(), anyLong(), any());
            verify(idempotencyService).markProcessed(eq("evt_c4"), eq(WebhookProcessStatus.IGNORED));
        }

        @Test
        @DisplayName("対象 subscription 不在（無関係 invoice）は上書きせず IGNORED（再送させない）")
        void unknownSubscription_noOverride() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_c5", "invoice.created", "sub_other", "in_5", "draft", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_c5"), any(), anyBoolean())).willReturn(true);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdAndDeletedAtIsNull("sub_other"))
                    .willReturn(Optional.empty());

            service.handleWebhook("payload", "sig");

            verify(stripePaymentProvider, never()).updateInvoiceApplicationFee(any(), anyLong(), any());
            verify(idempotencyService).markProcessed(eq("evt_c5"), eq(WebhookProcessStatus.IGNORED));
        }

        @Test
        @DisplayName("根治: 上書き API 失敗 → ERROR＋StripeWebhookRetryableException 再送出（FAILED 記録・握り潰さない）")
        void overrideFailure_rethrowsRetryable() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_c6", "invoice.created", "sub_x", "in_6", "draft", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_c6"), any(), anyBoolean())).willReturn(true);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdAndDeletedAtIsNull("sub_x"))
                    .willReturn(Optional.of(subscription(MembershipSubscriptionStatus.ACTIVE)));
            given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DEFAULT")).willReturn(Optional.empty());
            doThrow(new RuntimeException("stripe down"))
                    .when(stripePaymentProvider).updateInvoiceApplicationFee(eq("in_6"), anyLong(), anyString());

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(StripeWebhookRetryableException.class);

            verify(idempotencyService, times(1)).markFailed("evt_c6");
            verify(idempotencyService, never()).markProcessed(any(), any());
        }
    }

    @Nested
    @DisplayName("invoice.paid（記帳・状態反映）")
    class InvoicePaid {

        @Test
        @DisplayName("ACTIVE: escrow(CAPTURED)＋ledger 借貸一致(10000=9500+500)＋member_payments(PAID)＋current_period 更新")
        void active_recordsAndExtends() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_p1", "invoice.paid", "sub_x", "in_p1", "paid", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_p1"), any(), anyBoolean())).willReturn(true);
            MembershipSubscriptionEntity sub = subscription(MembershipSubscriptionStatus.ACTIVE);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_x"))
                    .willReturn(Optional.of(sub));
            given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DEFAULT")).willReturn(Optional.empty());
            given(escrowTransactionRepository.findByStripePaymentIntentId("pi_cycle")).willReturn(Optional.empty());
            given(escrowTransactionRepository.save(any())).willAnswer(inv -> {
                EscrowTransactionEntity e = inv.getArgument(0);
                e.setId(java.util.UUID.randomUUID());
                return e;
            });
            given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
            given(memberPaymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.handleWebhook("payload", "sig");

            // escrow CAPTURED・額面 10,000・appFee 500・transfer 9,500。
            ArgumentCaptor<EscrowTransactionEntity> escrowCaptor = ArgumentCaptor.forClass(EscrowTransactionEntity.class);
            verify(escrowTransactionRepository).save(escrowCaptor.capture());
            assertThat(escrowCaptor.getValue().getStatus()).isEqualTo(EscrowStatus.CAPTURED);
            assertThat(escrowCaptor.getValue().getApplicationFeeAmount()).isEqualTo(500L);
            assertThat(escrowCaptor.getValue().getFaceAmount()).isEqualTo(10_000L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LedgerEntryEntity>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
            verify(ledgerEntryRepository).saveAll(ledgerCaptor.capture());
            long debit = ledgerCaptor.getValue().stream().filter(e -> e.getDirection() == LedgerDirection.D)
                    .mapToLong(LedgerEntryEntity::getAmount).sum();
            long credit = ledgerCaptor.getValue().stream().filter(e -> e.getDirection() == LedgerDirection.C)
                    .mapToLong(LedgerEntryEntity::getAmount).sum();
            assertThat(debit).isEqualTo(credit).isEqualTo(10_000L);

            // member_payments(PAID)・valid_until=period_end（2024-07-01 相当の epoch）。
            ArgumentCaptor<MemberPaymentEntity> mpCaptor = ArgumentCaptor.forClass(MemberPaymentEntity.class);
            verify(memberPaymentRepository).save(mpCaptor.capture());
            assertThat(mpCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(mpCaptor.getValue().getMembershipSubscriptionId()).isEqualTo(SUB_ID);
            assertThat(mpCaptor.getValue().getValidUntil())
                    .isEqualTo(LocalDate.ofInstant(java.time.Instant.ofEpochSecond(1_719_792_000L),
                            java.time.ZoneId.systemDefault()));
            verify(idempotencyService).markProcessed(eq("evt_p1"), eq(WebhookProcessStatus.PROCESSED));
        }

        @Test
        @DisplayName("PAST_DUE: 再試行成功 → markRecovered で ACTIVE 復帰＋記帳")
        void pastDue_recovers() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_p2", "invoice.paid", "sub_x", "in_p2", "paid", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_p2"), any(), anyBoolean())).willReturn(true);
            MembershipSubscriptionEntity sub = subscription(MembershipSubscriptionStatus.PAST_DUE);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_x"))
                    .willReturn(Optional.of(sub));
            given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DEFAULT")).willReturn(Optional.empty());
            given(escrowTransactionRepository.findByStripePaymentIntentId("pi_cycle")).willReturn(Optional.empty());
            given(escrowTransactionRepository.save(any())).willAnswer(inv -> {
                EscrowTransactionEntity e = inv.getArgument(0);
                e.setId(java.util.UUID.randomUUID());
                return e;
            });
            given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
            given(memberPaymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.handleWebhook("payload", "sig");

            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.ACTIVE);
            verify(idempotencyService).markProcessed(eq("evt_p2"), eq(WebhookProcessStatus.PROCESSED));
        }

        @Test
        @DisplayName("冪等: 当該 PI で escrow 起票済みなら再起票しない（ledger/member_payments を二重に書かない）")
        void idempotent_noDoubleRecord() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_p3", "invoice.paid", "sub_x", "in_p3", "paid", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_p3"), any(), anyBoolean())).willReturn(true);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_x"))
                    .willReturn(Optional.of(subscription(MembershipSubscriptionStatus.ACTIVE)));
            given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            EscrowTransactionEntity existing = new EscrowTransactionEntity();
            given(escrowTransactionRepository.findByStripePaymentIntentId("pi_cycle"))
                    .willReturn(Optional.of(existing));

            service.handleWebhook("payload", "sig");

            verify(escrowTransactionRepository, never()).save(any());
            verify(ledgerEntryRepository, never()).saveAll(any());
            verify(memberPaymentRepository, never()).save(any());
            verify(idempotencyService).markProcessed(eq("evt_p3"), eq(WebhookProcessStatus.PROCESSED));
        }

        @Test
        @DisplayName("PENDING（案bでは想定外）: 本サービスでは markActive せず活性化点へ委譲する")
        void pending_delegatesToActivationPoint() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_p4", "invoice.paid", "sub_x", "in_p4", "paid", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_p4"), any(), anyBoolean())).willReturn(true);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_x"))
                    .willReturn(Optional.of(subscription(MembershipSubscriptionStatus.PENDING)));
            given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DEFAULT")).willReturn(Optional.empty());
            given(escrowTransactionRepository.findByStripePaymentIntentId("pi_cycle")).willReturn(Optional.empty());
            given(escrowTransactionRepository.save(any())).willAnswer(inv -> {
                EscrowTransactionEntity e = inv.getArgument(0);
                e.setId(java.util.UUID.randomUUID());
                return e;
            });
            given(ledgerEntryRepository.saveAll(any())).willAnswer(inv -> inv.getArgument(0));
            given(memberPaymentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.handleWebhook("payload", "sig");

            // PENDING→ACTIVE は本サービスで行わず、唯一の活性化点へ委譲する（二重発火防止）。
            verify(membershipSubscriptionService).activateOnInitialChargeIfPending(SUB_ID);
        }
    }

    @Nested
    @DisplayName("invoice.payment_failed / customer.subscription.deleted")
    class FailedAndDeleted {

        @Test
        @DisplayName("payment_failed: ACTIVE → PAST_DUE")
        void paymentFailed_pastDue() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_f1", "invoice.payment_failed", "sub_x", "in_f1", "open", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_f1"), any(), anyBoolean())).willReturn(true);
            MembershipSubscriptionEntity sub = subscription(MembershipSubscriptionStatus.ACTIVE);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_x"))
                    .willReturn(Optional.of(sub));
            given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.handleWebhook("payload", "sig");

            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.PAST_DUE);
            verify(idempotencyService).markProcessed(eq("evt_f1"), eq(WebhookProcessStatus.PROCESSED));
        }

        @Test
        @DisplayName("subscription.deleted: ACTIVE → CANCELLED＋cancelled_at")
        void deleted_cancelled() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    new StripePaymentProvider.InvoiceWebhookEventInfo(
                            "evt_d1", "customer.subscription.deleted", false, "sub_x",
                            null, null, null, null, null, null, null, null));
            given(idempotencyService.tryBegin(eq("evt_d1"), any(), anyBoolean())).willReturn(true);
            MembershipSubscriptionEntity sub = subscription(MembershipSubscriptionStatus.ACTIVE);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_x"))
                    .willReturn(Optional.of(sub));
            given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.handleWebhook("payload", "sig");

            assertThat(sub.getStatus()).isEqualTo(MembershipSubscriptionStatus.CANCELLED);
            assertThat(sub.getCancelledAt()).isNotNull();
            verify(idempotencyService).markProcessed(eq("evt_d1"), eq(WebhookProcessStatus.PROCESSED));
        }

        @Test
        @DisplayName("subscription.deleted: 既に CANCELLED は冪等 no-op（save しない）")
        void deleted_alreadyCancelled_noOp() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    new StripePaymentProvider.InvoiceWebhookEventInfo(
                            "evt_d2", "customer.subscription.deleted", false, "sub_x",
                            null, null, null, null, null, null, null, null));
            given(idempotencyService.tryBegin(eq("evt_d2"), any(), anyBoolean())).willReturn(true);
            given(membershipSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_x"))
                    .willReturn(Optional.of(subscription(MembershipSubscriptionStatus.CANCELLED)));

            service.handleWebhook("payload", "sig");

            verify(membershipSubscriptionRepository, never()).save(any());
            verify(idempotencyService).markProcessed(eq("evt_d2"), eq(WebhookProcessStatus.PROCESSED));
        }
    }

    @Nested
    @DisplayName("冪等・共通")
    class Idempotency {

        @Test
        @DisplayName("二重受信（同一 event_id）の 2 回目はハンドラを実行しない")
        void duplicateEvent_noOp() {
            given(stripePaymentProvider.constructInvoiceEvent(any(), any())).willReturn(
                    invoiceEvent("evt_dup", "invoice.created", "sub_x", "in_dup", "draft", "subscription_cycle"));
            given(idempotencyService.tryBegin(eq("evt_dup"), any(), anyBoolean())).willReturn(false);

            service.handleWebhook("payload", "sig");

            verify(membershipSubscriptionRepository, never()).findByStripeSubscriptionIdAndDeletedAtIsNull(any());
            verify(stripePaymentProvider, never()).updateInvoiceApplicationFee(any(), anyLong(), any());
            verify(idempotencyService, never()).markProcessed(any(), any());
        }

        @Test
        @DisplayName("isSubscriptionEvent: invoice.* / customer.subscription.deleted を判定")
        void isSubscriptionEvent() {
            assertThat(MembershipSubscriptionWebhookService.isSubscriptionEvent("invoice.created")).isTrue();
            assertThat(MembershipSubscriptionWebhookService.isSubscriptionEvent("invoice.paid")).isTrue();
            assertThat(MembershipSubscriptionWebhookService.isSubscriptionEvent("customer.subscription.deleted")).isTrue();
            assertThat(MembershipSubscriptionWebhookService.isSubscriptionEvent("payment_intent.succeeded")).isFalse();
            assertThat(MembershipSubscriptionWebhookService.isSubscriptionEvent("checkout.session.completed")).isFalse();
            assertThat(MembershipSubscriptionWebhookService.isSubscriptionEvent(null)).isFalse();
        }
    }
}
