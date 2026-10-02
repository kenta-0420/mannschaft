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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * BC-13 補償: Stripe Checkout Session の<b>作成に失敗</b>したとき、予約済み PENDING 契約を必ず解放する。
 *
 * <p><b>なぜ別クラスなのか</b>: 既存の試練（{@code BillingCheckoutApplicationServiceTrialTest}）は
 * 「Stripe <b>成功後</b>に DB が倒れた」経路しか観測していない。Stripe 作成<b>そのもの</b>が倒れる経路には
 * 補償が無く、孤児 PENDING が {@code uk_acp_slot} を占有して当該 scope の以後の購入を永久に
 * 016 で詰まらせていた（Session が存在しないため {@code checkout.session.expired} でも解放されない）。
 * 試練の期待値は一切変えず、欠けていた経路を新規に固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR4 Checkout: Stripe 作成失敗時の PENDING 補償")
class BillingCheckoutPendingCompensationTest {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final long ACTOR_ID = 7L;
    private static final long SCOPE_ID = 91L;
    private static final UUID QUOTE_ID = UUID.fromString("01999d74-5130-7000-8000-000000000030");
    private static final UUID CUSTOMER_ID = UUID.fromString("01999d74-5130-7000-8000-000000000031");
    private static final UUID BAND_ID = UUID.fromString("01999d74-5130-7000-8000-000000000032");
    private static final UUID CONTRACT_ID = UUID.fromString("01999d74-5130-7000-8000-000000000033");
    private static final String KEY = "00000000-0000-0000-0000-000000000301";

    private static final Instant JST_PERIOD_START = Instant.parse("2028-01-31T15:00:00Z");
    private static final Instant JST_PERIOD_END = Instant.parse("2028-02-29T15:00:00Z");

    @Mock private BillingCheckoutAccessGuard scopeGuard;
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
        service = new BillingCheckoutApplicationService(clock, scopeGuard, quoteRepository,
                customerRepository, priceRepository, contractRepository, idempotencyService,
                stripeCheckoutGateway, reconciliationQueue);
    }

    @Test
    @DisplayName("Stripe 作成が例外なら PENDING 契約を解放してから 015/502 を返す")
    void stripe作成失敗_PENDING契約を解放する() {
        arrange();
        willThrow(new IllegalStateException("stripe down"))
                .given(stripeCheckoutGateway).createSubscription(any());

        assertThatThrownBy(() -> service.create(ACTOR_ID,
                new CreateBillingCheckoutSessionRequest(QUOTE_ID), KEY))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getErrorCode())
                            .isEqualTo(EntitlementErrorCode.CHECKOUT_SESSION_FAILED);
                    assertThat(new GlobalExceptionHandler(new StaticMessageSource())
                            .handleBusinessException(exception).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_GATEWAY);
                });

        InOrder order = inOrder(contractRepository, stripeCheckoutGateway);
        order.verify(contractRepository).reservePendingContract(any(), org.mockito.ArgumentMatchers.eq(ACTOR_ID));
        order.verify(stripeCheckoutGateway).createSubscription(any());
        order.verify(contractRepository).abandonPendingContract(CONTRACT_ID);
    }

    @Test
    @DisplayName("Stripe 作成失敗時は quote を消費せず Session も無いので照合キューへは積まない")
    void stripe作成失敗_quote未消費かつ照合キュー不使用() {
        arrange();
        willThrow(new IllegalStateException("stripe down"))
                .given(stripeCheckoutGateway).createSubscription(any());

        assertThatThrownBy(() -> service.create(ACTOR_ID,
                new CreateBillingCheckoutSessionRequest(QUOTE_ID), KEY))
                .isInstanceOf(BusinessException.class);

        verify(quoteRepository, org.mockito.Mockito.never())
                .consumeIfUnchanged(any(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), any());
        verifyNoInteractions(reconciliationQueue);
    }

    @Test
    @DisplayName("補償自体が落ちても事実を失わず 015/502 を上申する（握りつぶさない）")
    void 補償失敗でも015を返す() {
        arrange();
        willThrow(new IllegalStateException("stripe down"))
                .given(stripeCheckoutGateway).createSubscription(any());
        willThrow(new IllegalStateException("db down"))
                .given(contractRepository).abandonPendingContract(CONTRACT_ID);

        assertThatThrownBy(() -> service.create(ACTOR_ID,
                new CreateBillingCheckoutSessionRequest(QUOTE_ID), KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CHECKOUT_SESSION_FAILED);

        verify(contractRepository).abandonPendingContract(CONTRACT_ID);
    }

    private void arrange() {
        BillingQuoteSnapshot quote = quote();
        given(quoteRepository.findById(QUOTE_ID)).willReturn(Optional.of(quote));
        given(customerRepository.findScopeOwnedActive(EntitlementScopeKind.TEAM, SCOPE_ID, CUSTOMER_ID))
                .willReturn(Optional.of(new BillingCheckoutCustomer(CUSTOMER_ID,
                        EntitlementScopeKind.TEAM, SCOPE_ID, "cus_scope_team_91", "ACTIVE")));
        given(priceRepository.isExistingSellablePrice(BAND_ID, "price_existing_pro_21")).willReturn(true);
        given(contractRepository.reservePendingContract(quote, ACTOR_ID)).willReturn(CONTRACT_ID);
    }

    private BillingQuoteSnapshot quote() {
        BillingMoney initial = new BillingMoney("JPY", 1000, 909, 91, "消費税", 1000);
        BillingMoney next = new BillingMoney("JPY", 3000, 2727, 273, "消費税", 1000);
        return new BillingQuoteSnapshot(QUOTE_ID, ACTOR_ID, EntitlementScopeKind.TEAM, SCOPE_ID,
                CUSTOMER_ID, BillingProductKind.PLAN, "PRO", BAND_ID, "price_existing_pro_21",
                21, initial, next, "{\"rateBasisPoints\":1000}",
                JST_PERIOD_START, JST_PERIOD_END, clock.instant(), null, "b".repeat(64),
                clock.instant().plusSeconds(600), null, 0L);
    }
}
