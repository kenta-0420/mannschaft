package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.BillingInterval;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.event.MembershipPayerWithdrawalNotificationEvent;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 柱③-B PR-3（CMP-260901-1538）AC-13: {@code cancelAllForPayerOnWithdrawal} の単体テスト。
 *
 * <p>設計書 {@code docs/architecture/billing_payer_handover_design.md} §6 の受け入れ条件
 * 「{@code payer_user_id} 一致かつ ACTIVE/PAST_DUE のみ期末解約し、受益者へ通知する」を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MembershipSubscriptionService#cancelAllForPayerOnWithdrawal（柱③-B PR-3・AC-13）")
class MembershipSubscriptionPayerWithdrawalTest {

    private static final Long PAYER_USER_ID = 4001L;
    private static final Long BENEFICIARY_USER_ID = 4002L;

    @Mock
    private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Mock
    private PaymentItemService paymentItemService;
    @Mock
    private com.mannschaft.app.payment.repository.PaymentItemRepository paymentItemRepository;
    @Mock
    private PaymentAuthorizationService paymentAuthorizationService;
    @Mock
    private com.mannschaft.app.payment.connect.ConnectAccountRepository connectAccountRepository;
    @Mock
    private com.mannschaft.app.payment.escrow.ConnectChargeService connectChargeService;
    @Mock
    private com.mannschaft.app.payment.FeePolicyResolver feePolicyResolver;
    @Mock
    private com.mannschaft.app.payment.repository.StripeCustomerRepository stripeCustomerRepository;
    @Mock
    private StripePaymentProvider stripePaymentProvider;
    @Mock
    private MemberPaymentService memberPaymentService;
    @Mock
    private com.mannschaft.app.auth.repository.UserRepository userRepository;
    @Mock
    private com.mannschaft.app.payment.PaymentFeeCalculator paymentFeeCalculator;
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private MembershipSubscriptionService service;

    /** ACTIVE の継続課金フィクスチャ（Stripe 連結済み・期末解約未予約）。 */
    private MembershipSubscriptionEntity activeSubscription(String stripeSubscriptionId) {
        return MembershipSubscriptionEntity.builder()
                .organizationId(10L)
                .paymentItemId(20L)
                .beneficiaryUserId(BENEFICIARY_USER_ID)
                .payerUserId(PAYER_USER_ID)
                .scopeKind(ScopeKind.TEAM)
                .scopeId(30L)
                .payeeConnectAccountId(java.util.UUID.randomUUID())
                .stripeSubscriptionId(stripeSubscriptionId)
                .billingInterval(BillingInterval.MONTHLY)
                .status(MembershipSubscriptionStatus.ACTIVE)
                .faceAmount(3000)
                .currentPeriodEnd(LocalDate.of(2026, 10, 31))
                .cancelAtPeriodEnd(false)
                .build();
    }

    @Test
    @DisplayName("AC-13 正常系: payer 一致の ACTIVE/PAST_DUE を Stripe 期末解約し DB へ反映する")
    void 正常_payer一致のACTIVEを期末解約する() {
        MembershipSubscriptionEntity subscription = activeSubscription("sub_active_1");
        given(membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(eq(PAYER_USER_ID), any()))
                .willReturn(List.of(subscription));
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(anyString(), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_active_1", "active", null));
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        List<String> result = service.cancelAllForPayerOnWithdrawal(PAYER_USER_ID);

        assertThat(result).containsExactly("sub_active_1");
        assertThat(subscription.getCancelAtPeriodEnd()).isTrue();
        verify(stripePaymentProvider).cancelSubscriptionAtPeriodEnd(eq("sub_active_1"), anyString());
        verify(membershipSubscriptionRepository).save(subscription);
    }

    @Test
    @DisplayName("AC-13 対象の絞り込み: 検索は payer 一致かつ ACTIVE/PAST_DUE のみを条件にする")
    void 対象_ACTIVEとPAST_DUEのみを検索する() {
        given(membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(eq(PAYER_USER_ID), any()))
                .willReturn(List.of());

        service.cancelAllForPayerOnWithdrawal(PAYER_USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<MembershipSubscriptionStatus>> statuses =
                ArgumentCaptor.forClass(Collection.class);
        verify(membershipSubscriptionRepository)
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
                        eq(PAYER_USER_ID), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                MembershipSubscriptionStatus.ACTIVE, MembershipSubscriptionStatus.PAST_DUE);
    }

    @Test
    @DisplayName("AC-13 通知: 受益者宛の期末解約予告イベントを publish する")
    void 通知_受益者へ期末解約を予告する() {
        MembershipSubscriptionEntity subscription = activeSubscription("sub_active_2");
        given(membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(eq(PAYER_USER_ID), any()))
                .willReturn(List.of(subscription));
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(anyString(), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_active_2", "active", null));
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.cancelAllForPayerOnWithdrawal(PAYER_USER_ID);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(MembershipPayerWithdrawalNotificationEvent.class);
        MembershipPayerWithdrawalNotificationEvent event =
                (MembershipPayerWithdrawalNotificationEvent) captor.getValue();
        assertThat(event.beneficiaryUserId()).isEqualTo(BENEFICIARY_USER_ID);
        assertThat(event.payerUserId()).isEqualTo(PAYER_USER_ID);
        assertThat(event.scopeKind()).isEqualTo(ScopeKind.TEAM);
        assertThat(event.currentPeriodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    @DisplayName("AC-13 冪等: 既に cancel_at_period_end=true の行は Stripe を叩き直さない")
    void 冪等_既に予約済みならStripeを呼ばない() {
        MembershipSubscriptionEntity subscription = activeSubscription("sub_already");
        subscription.scheduleCancelAtPeriodEnd();
        given(membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(eq(PAYER_USER_ID), any()))
                .willReturn(List.of(subscription));

        List<String> result = service.cancelAllForPayerOnWithdrawal(PAYER_USER_ID);

        assertThat(result).isEmpty();
        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
        verify(applicationEventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("AC-13 失敗の切り分け: 1件の Stripe 失敗が他の契約の期末解約を巻き添えにしない")
    void 失敗_1件のStripe失敗で残りを巻き添えにしない() {
        MembershipSubscriptionEntity failing = activeSubscription("sub_fail");
        MembershipSubscriptionEntity succeeding = activeSubscription("sub_ok");
        given(membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(eq(PAYER_USER_ID), any()))
                .willReturn(List.of(failing, succeeding));
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_fail"), anyString()))
                .willThrow(new IllegalStateException("Stripe 障害"));
        given(stripePaymentProvider.cancelSubscriptionAtPeriodEnd(eq("sub_ok"), anyString()))
                .willReturn(new StripePaymentProvider.SubscriptionInfo("sub_ok", "active", null));
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        List<String> result = service.cancelAllForPayerOnWithdrawal(PAYER_USER_ID);

        assertThat(result).containsExactly("sub_ok");
        assertThat(succeeding.getCancelAtPeriodEnd()).isTrue();
        verify(stripePaymentProvider, times(2)).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
    }

    @Test
    @DisplayName("AC-13 Stripe 未連結: DB のみ予約し、戻り値には含めない")
    void Stripe未連結_DBのみ予約する() {
        MembershipSubscriptionEntity subscription = activeSubscription(null);
        given(membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(eq(PAYER_USER_ID), any()))
                .willReturn(List.of(subscription));
        given(membershipSubscriptionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        List<String> result = service.cancelAllForPayerOnWithdrawal(PAYER_USER_ID);

        assertThat(result).isEmpty();
        assertThat(subscription.getCancelAtPeriodEnd()).isTrue();
        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
        verify(membershipSubscriptionRepository).save(subscription);
    }

    @Test
    @DisplayName("AC-13 対象なし: 該当契約が無ければ Stripe を一切呼ばず空を返す")
    void 対象なし_Stripeを呼ばない() {
        given(membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(anyLong(), any()))
                .willReturn(List.of());

        assertThat(service.cancelAllForPayerOnWithdrawal(PAYER_USER_ID)).isEmpty();
        verify(stripePaymentProvider, never()).cancelSubscriptionAtPeriodEnd(anyString(), anyString());
    }

    @Test
    @DisplayName("防御: payerUserId が null なら何もせず空を返す")
    void 防御_payerUserIdがnullなら何もしない() {
        assertThat(service.cancelAllForPayerOnWithdrawal(null)).isEmpty();
        verify(membershipSubscriptionRepository, never())
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(any(), any());
    }
}
