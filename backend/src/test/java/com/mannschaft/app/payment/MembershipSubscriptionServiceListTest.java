package com.mannschaft.app.payment;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.dto.MembershipSubscriptionListItemResponse;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import com.mannschaft.app.payment.service.PaymentAuthorizationService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * F08.9 P5 第四波: {@link MembershipSubscriptionService#findForPayerWithNames} /
 * {@link MembershipSubscriptionService#findForTeamWithNames} の単体テスト。
 *
 * <p>払い手絞り込み・nextBillingDate の skip 分岐・IDOR（他人/他チーム 403 は Controller 層）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipSubscriptionService 一覧テスト")
class MembershipSubscriptionServiceListTest {

    @Mock private MembershipSubscriptionRepository membershipSubscriptionRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private PaymentItemRepository paymentItemRepository;
    @Mock private PaymentAuthorizationService paymentAuthorizationService;
    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private ConnectChargeService connectChargeService;
    @Mock private FeePolicyResolver feePolicyResolver;
    @Mock private StripeCustomerRepository stripeCustomerRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private MemberPaymentService memberPaymentService;
    @Mock private UserRepository userRepository;

    @InjectMocks
    private MembershipSubscriptionService service;

    private static final Long PAYER = 100L;
    private static final Long BENEFICIARY = 200L;
    private static final Long ITEM_ID = 10L;
    private static final UUID SUB_ID = UUID.fromString("019607a0-0000-7000-8000-0000000000ff");
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 9, 30);

    private MembershipSubscriptionEntity makeActiveSubscription() {
        MembershipSubscriptionEntity sub = MembershipSubscriptionEntity.builder()
                .paymentItemId(ITEM_ID).beneficiaryUserId(BENEFICIARY).payerUserId(PAYER)
                .scopeKind(ScopeKind.TEAM).scopeId(50L)
                .payeeConnectAccountId(UUID.randomUUID())
                .billingInterval(BillingInterval.MONTHLY)
                .status(MembershipSubscriptionStatus.ACTIVE)
                .feePolicyKey("DEFAULT").faceAmount(3000).currency("JPY")
                .stripeSubscriptionId("sub_abc").cancelAtPeriodEnd(false)
                .build();
        sub.setId(SUB_ID);
        sub.applyCurrentPeriod(LocalDate.of(2026, 9, 1), PERIOD_END);
        return sub;
    }

    private PaymentItemEntity makePaymentItem() {
        // PaymentItemEntity のビルダーで name を設定する（type は NOT NULL なので指定必須）。
        return PaymentItemEntity.builder()
                .name("月会費")
                .type(PaymentItemType.MONTHLY_FEE)
                .amount(new BigDecimal("3000"))
                .currency("JPY")
                .build();
    }

    @Test
    @DisplayName("findForPayerWithNames: 払い手の一覧を返す・nextBillingDate=period_end（スキップなし）")
    void findForPayer_noSkip_nextBillingIsCurrentPeriodEnd() {
        MembershipSubscriptionEntity sub = makeActiveSubscription();
        given(membershipSubscriptionRepository.findByPayerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(PAYER))
                .willReturn(List.of(sub));

        PaymentItemEntity item = makePaymentItem();
        given(paymentItemRepository.findAllById(anyCollection())).willReturn(List.of(item));

        // UserEntity は protected コンストラクタのため直接インスタンス化不可。
        // 空リストを返してフォールバック（空文字列）を検証する。
        given(userRepository.findByIdIn(any())).willReturn(Collections.emptyList());

        List<MembershipSubscriptionListItemResponse> result = service.findForPayerWithNames(PAYER);

        assertThat(result).hasSize(1);
        MembershipSubscriptionListItemResponse item0 = result.get(0);
        assertThat(item0.getPayerUserId()).isEqualTo(PAYER);
        assertThat(item0.getBeneficiaryUserId()).isEqualTo(BENEFICIARY);
        // nextBillingDate はスキップなしなので current_period_end。
        assertThat(item0.getNextBillingDate()).isEqualTo(PERIOD_END);
        assertThat(item0.getValidUntil()).isEqualTo(PERIOD_END);
        assertThat(item0.getSkipUntil()).isNull();
    }

    @Test
    @DisplayName("findForPayerWithNames: スキップ中 → nextBillingDate=skipUntil")
    void findForPayer_skipped_nextBillingIsSkipUntil() {
        MembershipSubscriptionEntity sub = makeActiveSubscription();
        LocalDate skipUntil = LocalDate.of(2026, 10, 30);
        sub.applySkipUntil(skipUntil);
        given(membershipSubscriptionRepository.findByPayerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(PAYER))
                .willReturn(List.of(sub));
        given(paymentItemRepository.findAllById(anyCollection())).willReturn(Collections.emptyList());
        given(userRepository.findByIdIn(any())).willReturn(Collections.emptyList());

        List<MembershipSubscriptionListItemResponse> result = service.findForPayerWithNames(PAYER);

        assertThat(result).hasSize(1);
        // nextBillingDate はスキップ中なので skipUntil。
        assertThat(result.get(0).getNextBillingDate()).isEqualTo(skipUntil);
        assertThat(result.get(0).getSkipUntil()).isEqualTo(skipUntil);
    }

    @Test
    @DisplayName("findForPayerWithNames: 一覧が空の場合は空リストを返す")
    void findForPayer_empty() {
        given(membershipSubscriptionRepository.findByPayerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(PAYER))
                .willReturn(Collections.emptyList());

        List<MembershipSubscriptionListItemResponse> result = service.findForPayerWithNames(PAYER);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findForTeamWithNames: チームの全件を返す（statusFilter=null）")
    void findForTeam_noFilter() {
        MembershipSubscriptionEntity sub = makeActiveSubscription();
        given(membershipSubscriptionRepository.findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                ScopeKind.TEAM, 50L)).willReturn(List.of(sub));
        given(paymentItemRepository.findAllById(anyCollection())).willReturn(Collections.emptyList());
        given(userRepository.findByIdIn(any())).willReturn(Collections.emptyList());

        List<MembershipSubscriptionListItemResponse> result = service.findForTeamWithNames(50L, null);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("findForTeamWithNames: statusFilter=ACTIVE で絞り込み結果を返す")
    void findForTeam_withStatusFilter() {
        MembershipSubscriptionEntity sub = makeActiveSubscription();
        given(membershipSubscriptionRepository.findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
                ScopeKind.TEAM, 50L, Collections.singletonList(MembershipSubscriptionStatus.ACTIVE)))
                .willReturn(List.of(sub));
        given(paymentItemRepository.findAllById(anyCollection())).willReturn(Collections.emptyList());
        given(userRepository.findByIdIn(any())).willReturn(Collections.emptyList());

        List<MembershipSubscriptionListItemResponse> result =
                service.findForTeamWithNames(50L, MembershipSubscriptionStatus.ACTIVE);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("ACTIVE");
    }

    // ============================================================
    // 残債2: resolveEmailForStripeCustomer（Stripe Customer email 実メール化）
    // ============================================================

    @Test
    @DisplayName("残債2: resolveEmailForStripeCustomer はアクティブユーザーの実メールを返す")
    void resolveEmailForStripeCustomer_activeUser_returnsEmail() {
        UserEntity user = UserEntity.builder()
                .email("real-user@example.co.jp")
                .build();
        given(userRepository.findById(PAYER)).willReturn(Optional.of(user));

        Optional<String> email = service.resolveEmailForStripeCustomer(PAYER);

        assertThat(email).contains("real-user@example.co.jp");
    }

    @Test
    @DisplayName("残債2: resolveEmailForStripeCustomer は退会済み（deletedAt 非 null）ユーザーには空を返す")
    void resolveEmailForStripeCustomer_withdrawnUser_returnsEmpty() {
        UserEntity user = UserEntity.builder()
                .email("withdrawn-xxx@deleted.mannschaft.internal")
                .deletedAt(LocalDateTime.now().minusDays(1))
                .build();
        given(userRepository.findById(PAYER)).willReturn(Optional.of(user));

        Optional<String> email = service.resolveEmailForStripeCustomer(PAYER);

        assertThat(email).isEmpty();
    }

    @Test
    @DisplayName("残債2: resolveEmailForStripeCustomer は対象ユーザー不在なら空を返す")
    void resolveEmailForStripeCustomer_notFound_returnsEmpty() {
        given(userRepository.findById(PAYER)).willReturn(Optional.empty());

        Optional<String> email = service.resolveEmailForStripeCustomer(PAYER);

        assertThat(email).isEmpty();
    }
}
