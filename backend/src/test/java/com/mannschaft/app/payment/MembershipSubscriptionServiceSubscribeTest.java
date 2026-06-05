package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.service.PaymentAuthorizationService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.9 P5 第二波: {@link MembershipSubscriptionService#subscribe} の単体テスト。
 *
 * <p>権原評価 consume・is_recurring 409・READY 未達 409・PM 未保存 409・二重加入 409・fee_policy_key 焼付・
 * charge 引数（face/payer/payee/idempotencyKey）・Subscription 作成引数（anchor/transfer/default PM）・
 * charge 後 DB 失敗→再 throw を検証する。Stripe/escrow/Repository/認可をすべてモックする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipSubscriptionService subscribe 単体テスト")
class MembershipSubscriptionServiceSubscribeTest {

    @Mock private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private PaymentAuthorizationService paymentAuthorizationService;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private FeePolicyResolver feePolicyResolver;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private MemberPaymentService memberPaymentService;

    @InjectMocks
    private MembershipSubscriptionService service;

    private static final Long ITEM_ID = 1L;
    private static final Long TEAM_ID = 50L;
    private static final Long ORG_ID = 7L;
    private static final Long BENEFICIARY = 100L;
    private static final Long PAYER = 100L;
    private static final Long OTHER_PAYER = 999L;
    private static final String IDEMPOTENCY_KEY = "idem-sub-001";
    private static final UUID ESCROW_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");

    private PaymentItemEntity recurringItem() {
        return PaymentItemEntity.builder()
                .teamId(TEAM_ID)
                .organizationId(ORG_ID)
                .name("月会費")
                .type(PaymentItemType.MONTHLY_FEE)
                .amount(new BigDecimal("3000"))
                .currency("JPY")
                .isRecurring(true)
                .billingInterval(BillingInterval.MONTHLY)
                .build();
    }

    private ConnectAccountEntity readyAccount() {
        ConnectAccountEntity acc = ConnectAccountEntity.builder()
                .scopeKind(ScopeKind.TEAM).scopeId(TEAM_ID)
                .stripeAccountId("acct_ready").payoutsEnabled(true).chargesEnabled(true)
                .build();
        acc.setId(ACCOUNT_ID);
        return acc;
    }

    private StripeCustomerEntity customerWithPm() {
        return StripeCustomerEntity.builder()
                .userId(PAYER).stripeCustomerId("cus_payer").defaultPaymentMethod("pm_saved").build();
    }

    private void stubHappyPathUpTo() {
        given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(recurringItem());
        given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                .willReturn(PayerRelationship.SELF);
        given(membershipSubscriptionRepository
                .existsByBeneficiaryUserIdAndPaymentItemIdAndStatusInAndDeletedAtIsNull(eq(BENEFICIARY), eq(ITEM_ID), any()))
                .willReturn(false);
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                .willReturn(Optional.of(readyAccount()));
        given(stripeCustomerRepository.findByUserId(PAYER)).willReturn(Optional.of(customerWithPm()));
        given(feePolicyResolver.resolve(EscrowSourceKind.MEMBERSHIP, null))
                .willReturn(new FeePolicy("MEMBERSHIP_RANK_A", new BigDecimal("0.03"), 0L));
        given(connectChargeService.charge(any(MembershipChargeCommand.class)))
                .willReturn(new MembershipChargeResult(ESCROW_ID, "cs_secret", "pi_123", EscrowStatus.AUTHORIZED));
    }

    @Nested
    @DisplayName("subscribe 正常系")
    class SubscribeHappy {

        @Test
        @DisplayName("正常系: 権原評価を通り初回 charge＋Subscription 作成＋PENDING 起票")
        void 加入してPENDING起票() {
            stubHappyPathUpTo();
            given(stripePaymentProvider.createProduct(anyString(), any())).willReturn("prod_x");
            given(stripePaymentProvider.createRecurringPrice(eq("prod_x"), any(), eq("JPY"), eq(BillingInterval.MONTHLY)))
                    .willReturn("price_recurring");
            given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> {
                MembershipSubscriptionEntity e = inv.getArgument(0);
                if (e.getId() == null) {
                    e.setId(SUB_ID);
                }
                return e;
            });
            given(stripePaymentProvider.createSubscription(
                    eq("cus_payer"), eq("price_recurring"), eq("pm_saved"), eq("acct_ready"),
                    any(), anyLong(), anyString()))
                    .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_abc", "active", null));

            MembershipSubscriptionEntity result = service.subscribe(
                    ITEM_ID, PAYER, BENEFICIARY, (short) 15, IDEMPOTENCY_KEY);

            // 権原評価を必ず呼ぶ（consume 検証）。
            verify(paymentAuthorizationService).authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false);

            // charge 引数（face/payer Customer/payer/idempotencyKey/payee 口座）。
            ArgumentCaptor<MembershipChargeCommand> cmd = ArgumentCaptor.forClass(MembershipChargeCommand.class);
            verify(connectChargeService).charge(cmd.capture());
            assertThat(cmd.getValue().faceAmount()).isEqualTo(3000L);
            assertThat(cmd.getValue().payerStripeCustomerId()).isEqualTo("cus_payer");
            assertThat(cmd.getValue().payerUserId()).isEqualTo(PAYER);
            assertThat(cmd.getValue().payeeConnectAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(cmd.getValue().sourceId()).isEqualTo(ITEM_ID);
            assertThat(cmd.getValue().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);

            // Subscription 作成引数（default PM・transfer destination・anchor は将来時刻）。
            ArgumentCaptor<Long> anchor = ArgumentCaptor.forClass(Long.class);
            verify(stripePaymentProvider).createSubscription(
                    eq("cus_payer"), eq("price_recurring"), eq("pm_saved"), eq("acct_ready"),
                    eq(MembershipSubscriptionService.SAFE_DEFAULT_APPLICATION_FEE_PERCENT),
                    anchor.capture(), anyString());
            assertThat(anchor.getValue()).isGreaterThan(Instant.now().getEpochSecond());

            // member_payment 起票（subscription 連結）。
            verify(memberPaymentService).recordSubscriptionInitialChargePending(
                    eq(BENEFICIARY), eq(ITEM_ID), any(), eq("JPY"), eq(PAYER), eq(PayerRelationship.SELF),
                    eq(ESCROW_ID), eq(SUB_ID));

            // 起票内容: PENDING・fee_policy_key 焼付・face/currency price-lock・Stripe ID 連結。
            assertThat(result.getStatus()).isEqualTo(MembershipSubscriptionStatus.PENDING);
            assertThat(result.getFeePolicyKey()).isEqualTo("MEMBERSHIP_RANK_A");
            assertThat(result.getFaceAmount()).isEqualTo(3000);
            assertThat(result.getCurrency()).isEqualTo("JPY");
            assertThat(result.getBillingInterval()).isEqualTo(BillingInterval.MONTHLY);
            assertThat(result.getBillingAnchorDay()).isEqualTo((short) 15);
            assertThat(result.getStripeSubscriptionId()).isEqualTo("sub_abc");
            assertThat(result.getStripeCustomerId()).isEqualTo("cus_payer");
            assertThat(result.getPayeeConnectAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(result.getScopeKind()).isEqualTo(ScopeKind.TEAM);
        }
    }

    @Nested
    @DisplayName("subscribe 異常系")
    class SubscribeErrors {

        @Test
        @DisplayName("is_recurring=false の項目は 409・charge しない")
        void 非継続項目は409() {
            PaymentItemEntity oneTime = recurringItem().toBuilder().isRecurring(false).build();
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(oneTime);

            assertThatThrownBy(() -> service.subscribe(ITEM_ID, PAYER, BENEFICIARY, null, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.SUBSCRIPTION_ITEM_NOT_RECURRING);

            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("無権原は 403・charge しない")
        void 無権原は403() {
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(recurringItem());
            given(paymentAuthorizationService.authorizePayment(OTHER_PAYER, BENEFICIARY, ITEM_ID, false))
                    .willThrow(new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED));

            assertThatThrownBy(() -> service.subscribe(ITEM_ID, OTHER_PAYER, BENEFICIARY, null, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);

            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("二重加入は 409・charge しない")
        void 二重加入は409() {
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(recurringItem());
            given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                    .willReturn(PayerRelationship.SELF);
            given(membershipSubscriptionRepository
                    .existsByBeneficiaryUserIdAndPaymentItemIdAndStatusInAndDeletedAtIsNull(eq(BENEFICIARY), eq(ITEM_ID), any()))
                    .willReturn(true);

            assertThatThrownBy(() -> service.subscribe(ITEM_ID, PAYER, BENEFICIARY, null, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.SUBSCRIPTION_ALREADY_EXISTS);

            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("受領口座が非 READY は 409・charge しない")
        void 口座非READYは409() {
            ConnectAccountEntity notReady = ConnectAccountEntity.builder()
                    .scopeKind(ScopeKind.TEAM).scopeId(TEAM_ID)
                    .stripeAccountId("acct_x").payoutsEnabled(false).chargesEnabled(false).build();
            notReady.setId(ACCOUNT_ID);

            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(recurringItem());
            given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                    .willReturn(PayerRelationship.SELF);
            given(membershipSubscriptionRepository
                    .existsByBeneficiaryUserIdAndPaymentItemIdAndStatusInAndDeletedAtIsNull(eq(BENEFICIARY), eq(ITEM_ID), any()))
                    .willReturn(false);
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                    .willReturn(Optional.of(notReady));

            assertThatThrownBy(() -> service.subscribe(ITEM_ID, PAYER, BENEFICIARY, null, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);

            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("default PM 未保存は 409・charge しない")
        void PM未保存は409() {
            given(paymentItemService.findByIdOrThrow(ITEM_ID)).willReturn(recurringItem());
            given(paymentAuthorizationService.authorizePayment(PAYER, BENEFICIARY, ITEM_ID, false))
                    .willReturn(PayerRelationship.SELF);
            given(membershipSubscriptionRepository
                    .existsByBeneficiaryUserIdAndPaymentItemIdAndStatusInAndDeletedAtIsNull(eq(BENEFICIARY), eq(ITEM_ID), any()))
                    .willReturn(false);
            given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, TEAM_ID))
                    .willReturn(Optional.of(readyAccount()));
            given(stripeCustomerRepository.findByUserId(PAYER))
                    .willReturn(Optional.of(StripeCustomerEntity.builder()
                            .userId(PAYER).stripeCustomerId("cus_payer").build())); // default PM 無し

            assertThatThrownBy(() -> service.subscribe(ITEM_ID, PAYER, BENEFICIARY, null, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBillingErrorCode.SUBSCRIPTION_PAYMENT_METHOD_NOT_SAVED);

            verify(connectChargeService, never()).charge(any());
        }

        @Test
        @DisplayName("charge 成功後の DB 処理失敗は再 throw（症状を隠さない・P7 §11.1 同型）")
        void charge後DB失敗は再throw() {
            stubHappyPathUpTo();
            given(stripePaymentProvider.createProduct(anyString(), any())).willReturn("prod_x");
            given(stripePaymentProvider.createRecurringPrice(any(), any(), any(), any())).willReturn("price_recurring");
            // 最初の subscription save で DB 例外（charge は成功済み）。
            given(membershipSubscriptionRepository.save(any()))
                    .willThrow(new RuntimeException("DB down"));

            assertThatThrownBy(() -> service.subscribe(ITEM_ID, PAYER, BENEFICIARY, null, IDEMPOTENCY_KEY))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("DB down");

            // charge は呼ばれている（成功後の DB 失敗）。
            verify(connectChargeService).charge(any());
            // Subscription 作成までは到達しない。
            verify(stripePaymentProvider, never()).createSubscription(
                    anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyString());
        }
    }
}
