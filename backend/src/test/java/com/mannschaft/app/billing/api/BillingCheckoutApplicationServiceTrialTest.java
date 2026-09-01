package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.CreateBillingCheckoutSessionRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** BC-13/23: Checkout直前再検証・Stripe metadata・補償の試練。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR4 Checkout application service 試練")
class BillingCheckoutApplicationServiceTrialTest {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final long ACTOR_ID = 7L;
    private static final long SCOPE_ID = 91L;
    private static final UUID QUOTE_ID = UUID.fromString("01999d74-5130-7000-8000-000000000010");
    private static final UUID CUSTOMER_ID = UUID.fromString("01999d74-5130-7000-8000-000000000011");
    private static final UUID BAND_ID = UUID.fromString("01999d74-5130-7000-8000-000000000012");
    private static final UUID CONTRACT_ID = UUID.fromString("01999d74-5130-7000-8000-000000000013");

    /** clock=2028-02-10T03:00Z（JST 2/10 12:00）から導かれる JST 当月 period。 */
    private static final Instant JST_PERIOD_START = Instant.parse("2028-01-31T15:00:00Z");
    private static final Instant JST_PERIOD_END = Instant.parse("2028-02-29T15:00:00Z");

    @Mock private BillingCheckoutScopeGuard scopeGuard;
    @Mock private BillingQuoteRepository quoteRepository;
    @Mock private BillingCheckoutCustomerRepository customerRepository;
    @Mock private BillingCheckoutPriceRepository priceRepository;
    @Mock private BillingCheckoutContractRepository contractRepository;
    @Mock private BillingDurableIdempotencyService idempotencyService;
    @Mock private BillingStripeCheckoutGateway stripeCheckoutGateway;
    @Mock private BillingCheckoutReconciliationQueue reconciliationQueue;

    private Clock clock;
    private BillingCheckoutApplicationService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2028-02-10T03:00:00Z"), JST);
        service = newService(clock);
    }

    @Test
    @DisplayName("BC-13: Checkout前にactor/Guard/scope/Customer/価格/全snapshotを再検証してからquoteをCAS消費する")
    void checkout_有効quote_外部呼出し前に所有権とsnapshotを順序どおり再検証する() {
        BillingQuoteSnapshot quote = quote(clock.instant().plusSeconds(600));
        BillingCheckoutCustomer customer = activeCustomer();
        given(quoteRepository.findById(QUOTE_ID)).willReturn(Optional.of(quote));
        given(customerRepository.findScopeOwnedActive(EntitlementScopeKind.TEAM, SCOPE_ID, CUSTOMER_ID))
                .willReturn(Optional.of(customer));
        given(priceRepository.isExistingSellablePrice(BAND_ID, "price_existing_pro_21")).willReturn(true);
        given(contractRepository.reservePendingContract(quote, ACTOR_ID)).willReturn(CONTRACT_ID);
        given(stripeCheckoutGateway.createSubscription(any())).willReturn(
                new BillingStripeCheckoutResult("cs_test_1", "https://checkout.stripe.test/c/cs_test_1",
                        clock.instant().plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59))));
        given(quoteRepository.consumeIfUnchanged(QUOTE_ID, ACTOR_ID, 0L, clock.instant())).willReturn(1);

        service.create(ACTOR_ID, new CreateBillingCheckoutSessionRequest(QUOTE_ID),
                "00000000-0000-0000-0000-000000000101");

        InOrder order = inOrder(scopeGuard, customerRepository, priceRepository,
                contractRepository, stripeCheckoutGateway, quoteRepository);
        order.verify(scopeGuard).check(ACTOR_ID, EntitlementScopeKind.TEAM, SCOPE_ID);
        order.verify(customerRepository).findScopeOwnedActive(EntitlementScopeKind.TEAM, SCOPE_ID, CUSTOMER_ID);
        order.verify(priceRepository).isExistingSellablePrice(BAND_ID, "price_existing_pro_21");
        order.verify(contractRepository).reservePendingContract(quote, ACTOR_ID);
        order.verify(stripeCheckoutGateway).createSubscription(any());
        order.verify(quoteRepository).consumeIfUnchanged(QUOTE_ID, ACTOR_ID, 0L, clock.instant());
    }

    @Test
    @DisplayName("BC-13: 価格/人数/税/period/prorationのどれかが変わればquote未消費の023 QUOTE_STALE")
    void checkout_snapshotがstale_quote未消費で409にする() {
        // 旧 period（2028-01 相当）を抱えた本当に stale な検体。
        BillingQuoteSnapshot stale = quote(clock.instant().plusSeconds(600),
                Instant.parse("2027-12-31T15:00:00Z"), Instant.parse("2028-01-31T15:00:00Z"),
                Instant.parse("2028-01-10T03:00:00Z"));
        given(quoteRepository.findById(QUOTE_ID)).willReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.create(ACTOR_ID,
                new CreateBillingCheckoutSessionRequest(QUOTE_ID),
                "00000000-0000-0000-0000-000000000102"))
                .isInstanceOf(BillingConflictException.class)
                .satisfies(error -> {
                    BillingConflictException conflict = (BillingConflictException) error;
                    assertThat(conflict.getErrorCode()).isEqualTo(EntitlementErrorCode.QUOTE_EXPIRED);
                    assertThat(conflict.getDetails().reason()).isEqualTo(BillingConflictException.Reason.QUOTE_STALE);
                    assertThat(conflict.getDetails().quoteId()).isEqualTo(QUOTE_ID);
                    assertThat(new GlobalExceptionHandler(new StaticMessageSource())
                            .handleBusinessException(conflict).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(quoteRepository, never()).consumeIfUnchanged(any(), eq(ACTOR_ID), eq(0L), any());
        verifyNoInteractions(stripeCheckoutGateway);
    }

    @Test
    @DisplayName("BC-13: scope-owned ACTIVE CustomerでなければStripeを呼ばず409にする")
    void checkout_CustomerがACTIVEでない_Stripeを呼ばない() {
        BillingQuoteSnapshot quote = quote(clock.instant().plusSeconds(600));
        given(quoteRepository.findById(QUOTE_ID)).willReturn(Optional.of(quote));
        given(customerRepository.findScopeOwnedActive(EntitlementScopeKind.TEAM, SCOPE_ID, CUSTOMER_ID))
                .willReturn(Optional.empty());

        assertBusinessError(() -> service.create(ACTOR_ID,
                        new CreateBillingCheckoutSessionRequest(QUOTE_ID),
                        "00000000-0000-0000-0000-000000000103"),
                EntitlementErrorCode.MIGRATION_REQUIRED, HttpStatus.CONFLICT);
        verifyNoInteractions(stripeCheckoutGateway);
    }

    @Test
    @DisplayName("BC-13: 保存済みStripe Priceが存在し販売可能でなければ019でStripeを呼ばない")
    void checkout_StripePriceが販売不能_019で拒否する() {
        BillingQuoteSnapshot quote = quote(clock.instant().plusSeconds(600));
        given(quoteRepository.findById(QUOTE_ID)).willReturn(Optional.of(quote));
        given(customerRepository.findScopeOwnedActive(EntitlementScopeKind.TEAM, SCOPE_ID, CUSTOMER_ID))
                .willReturn(Optional.of(activeCustomer()));
        given(priceRepository.isExistingSellablePrice(BAND_ID, "price_existing_pro_21")).willReturn(false);

        assertBusinessError(() -> service.create(ACTOR_ID,
                        new CreateBillingCheckoutSessionRequest(QUOTE_ID),
                        "00000000-0000-0000-0000-000000000104"),
                EntitlementErrorCode.PRICE_NOT_SELLABLE, HttpStatus.CONFLICT);
        verifyNoInteractions(stripeCheckoutGateway);
    }

    @Test
    @DisplayName("BC-13: Sessionは既存Priceとsession/subscription両metadata4点を使い通常時23時間59分で失効する")
    void checkout_StripeRequest_既存Priceとmetadata4点とexpiryを固定する() {
        arrangeSuccessfulCheckout(clock.instant().plusSeconds(600));

        service.create(ACTOR_ID, new CreateBillingCheckoutSessionRequest(QUOTE_ID),
                "00000000-0000-0000-0000-000000000105");

        ArgumentCaptor<BillingStripeCheckoutRequest> captor =
                ArgumentCaptor.forClass(BillingStripeCheckoutRequest.class);
        verify(stripeCheckoutGateway).createSubscription(captor.capture());
        BillingStripeCheckoutRequest stripeRequest = captor.getValue();
        Map<String, String> expectedMetadata = Map.of(
                "billingContractId", CONTRACT_ID.toString(),
                "scopeKind", "TEAM",
                "scopeId", Long.toString(SCOPE_ID),
                "billingCustomerId", CUSTOMER_ID.toString());
        assertThat(stripeRequest.stripePriceRef()).isEqualTo("price_existing_pro_21");
        assertThat(stripeRequest.sessionMetadata()).containsExactlyInAnyOrderEntriesOf(expectedMetadata);
        assertThat(stripeRequest.subscriptionMetadata()).containsExactlyInAnyOrderEntriesOf(expectedMetadata);
        assertThat(Duration.between(clock.instant(), stripeRequest.expiresAt()))
                .isEqualTo(Duration.ofHours(23).plusMinutes(59));
    }

    @Test
    @DisplayName("BC-13: 月末許可境界ではSession expiryを翌月初60秒前に切り詰める")
    void checkout_月末許可境界_sessionExpiryは翌月初60秒前() {
        // fixture の共通 arrange は field の clock を基準に stub するため、月末時刻も field へ差し替える。
        clock = Clock.fixed(Instant.parse("2028-02-29T14:29:00Z"), JST);
        service = newService(clock);
        arrangeSuccessfulCheckout(clock.instant().plusSeconds(600));

        service.create(ACTOR_ID, new CreateBillingCheckoutSessionRequest(QUOTE_ID),
                "00000000-0000-0000-0000-000000000106");

        ArgumentCaptor<BillingStripeCheckoutRequest> captor =
                ArgumentCaptor.forClass(BillingStripeCheckoutRequest.class);
        verify(stripeCheckoutGateway).createSubscription(captor.capture());
        assertThat(captor.getValue().expiresAt()).isEqualTo(Instant.parse("2028-02-29T14:59:00Z"));
    }

    @Test
    @DisplayName("BC-23: Stripe成功後DB更新失敗はsession/customer参照を照合キューへ残し再送でStripeを二重実行しない")
    void checkout_Stripe成功後DB失敗_reconcileへ送り二重作成しない() {
        arrangeSuccessfulCheckout(clock.instant().plusSeconds(600));
        willThrow(new IllegalStateException("db unavailable"))
                .given(contractRepository).attachStripeSession(CONTRACT_ID, "cs_test_1");

        assertBusinessError(() -> service.create(ACTOR_ID,
                        new CreateBillingCheckoutSessionRequest(QUOTE_ID),
                        "00000000-0000-0000-0000-000000000107"),
                EntitlementErrorCode.STRIPE_UNAVAILABLE, HttpStatus.BAD_GATEWAY);

        verify(reconciliationQueue).enqueue(eq("cs_test_1"), eq("cus_scope_team_91"), any(UUID.class));
        verify(stripeCheckoutGateway).createSubscription(any());
    }

    private BillingCheckoutApplicationService newService(Clock useClock) {
        return new BillingCheckoutApplicationService(useClock, scopeGuard, quoteRepository,
                customerRepository, priceRepository, contractRepository, idempotencyService,
                stripeCheckoutGateway, reconciliationQueue);
    }

    private void arrangeSuccessfulCheckout(Instant quoteExpiry) {
        BillingQuoteSnapshot quote = quote(quoteExpiry);
        given(quoteRepository.findById(QUOTE_ID)).willReturn(Optional.of(quote));
        given(customerRepository.findScopeOwnedActive(EntitlementScopeKind.TEAM, SCOPE_ID, CUSTOMER_ID))
                .willReturn(Optional.of(activeCustomer()));
        given(priceRepository.isExistingSellablePrice(BAND_ID, "price_existing_pro_21")).willReturn(true);
        given(contractRepository.reservePendingContract(quote, ACTOR_ID)).willReturn(CONTRACT_ID);
        given(stripeCheckoutGateway.createSubscription(any())).willReturn(
                new BillingStripeCheckoutResult("cs_test_1", "https://checkout.stripe.test/c/cs_test_1",
                        clock.instant().plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59))));
        given(quoteRepository.consumeIfUnchanged(QUOTE_ID, ACTOR_ID, 0L, clock.instant())).willReturn(1);
    }

    private BillingCheckoutCustomer activeCustomer() {
        return new BillingCheckoutCustomer(CUSTOMER_ID, EntitlementScopeKind.TEAM, SCOPE_ID,
                "cus_scope_team_91", "ACTIVE");
    }

    /** JST 当月 period と整合した正常な quote 検体。 */
    private BillingQuoteSnapshot quote(Instant expiresAt) {
        return quote(expiresAt, JST_PERIOD_START, JST_PERIOD_END, clock.instant());
    }

    /** period/proration を差し替えられる quote 検体（stale 検体の生成用）。 */
    private BillingQuoteSnapshot quote(Instant expiresAt, Instant periodStart, Instant periodEnd,
                                       Instant prorationAt) {
        BillingMoney initial = new BillingMoney("JPY", 1000, 909, 91, "消費税", 1000);
        BillingMoney next = new BillingMoney("JPY", 3000, 2727, 273, "消費税", 1000);
        return new BillingQuoteSnapshot(QUOTE_ID, ACTOR_ID, EntitlementScopeKind.TEAM, SCOPE_ID,
                CUSTOMER_ID, BillingProductKind.PLAN, "PRO", BAND_ID, "price_existing_pro_21",
                21, initial, next, "{\"rateBasisPoints\":1000}",
                periodStart, periodEnd,
                prorationAt, null, "b".repeat(64), expiresAt, null, 0L);
    }

    private void assertBusinessError(Runnable action, EntitlementErrorCode code, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(code);
                    assertThat(new GlobalExceptionHandler(new StaticMessageSource())
                            .handleBusinessException(exception).getStatusCode()).isEqualTo(status);
                });
    }
}
