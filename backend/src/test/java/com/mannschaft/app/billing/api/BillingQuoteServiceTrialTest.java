package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.CreateBillingQuoteRequest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** BC-03/13: 10分 quote と JST 月境界の試練。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR4 quote snapshot 試練")
class BillingQuoteServiceTrialTest {
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final long ACTOR_ID = 7L;
    private static final long SCOPE_ID = 91L;
    private static final UUID CUSTOMER_ID = UUID.fromString("01999d74-5130-7000-8000-000000000001");
    private static final UUID BAND_ID = UUID.fromString("01999d74-5130-7000-8000-000000000002");
    private static final UUID QUOTE_ID = UUID.fromString("01999d74-5130-7000-8000-000000000003");

    @Mock private BillingCheckoutScopeGuard scopeGuard;
    @Mock private BillingQuoteRepository quoteRepository;
    @Mock private BillingQuoteCalculator quoteCalculator;

    private Clock clock;
    private BillingQuoteService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2028-02-29T05:00:00Z"), JST);
        service = new BillingQuoteService(clock, scopeGuard, quoteRepository, quoteCalculator);
    }

    @Test
    @DisplayName("BC-13: quoteは価格band・人数・税・period・prorationを保存し正確に10分で失効する")
    void quote_有効入力_snapshot全項目と10分expiryを保存する() {
        CreateBillingQuoteRequest request = request(EntitlementScopeKind.TEAM, SCOPE_ID, "PRO");
        BillingQuoteSnapshot calculated = snapshot(clock.instant().plus(Duration.ofMinutes(10)));
        given(quoteCalculator.calculate(ACTOR_ID, request, clock.instant())).willReturn(calculated);
        given(quoteRepository.save(calculated)).willReturn(calculated);

        var response = service.create(ACTOR_ID, request, "00000000-0000-0000-0000-000000000001");

        ArgumentCaptor<BillingQuoteSnapshot> captor = ArgumentCaptor.forClass(BillingQuoteSnapshot.class);
        verify(quoteRepository).save(captor.capture());
        BillingQuoteSnapshot saved = captor.getValue();
        assertThat(Duration.between(clock.instant(), saved.expiresAt())).isEqualTo(Duration.ofMinutes(10));
        assertThat(saved).extracting(
                        BillingQuoteSnapshot::priceBandVersionId,
                        BillingQuoteSnapshot::memberCount,
                        BillingQuoteSnapshot::taxSnapshot,
                        BillingQuoteSnapshot::periodStart,
                        BillingQuoteSnapshot::periodEnd,
                        BillingQuoteSnapshot::prorationAt)
                .containsExactly(BAND_ID, 21, "{\"rateBasisPoints\":1000}",
                        Instant.parse("2028-02-01T00:00:00Z"),
                        Instant.parse("2028-03-01T00:00:00Z"), clock.instant());
        assertThat(response.initialTotal().amountIncludingTax()).isEqualTo(1000L);
        assertThat(response.nextMonthlyTotal().amountIncludingTax()).isEqualTo(3000L);
    }

    @ParameterizedTest(name = "scopeId={0}")
    @ValueSource(longs = {0L, -1L})
    @DisplayName("BC-13: scopeIdが0以下ならStripe/Repositoryへ到達せず400にする")
    void quote_scopeIdが0以下_入力不備を拒否する(long invalidScopeId) {
        assertBusinessError(
                () -> service.create(ACTOR_ID,
                        request(EntitlementScopeKind.TEAM, invalidScopeId, "PRO"),
                        "00000000-0000-0000-0000-000000000002"),
                EntitlementErrorCode.INVALID_SCOPE_KIND,
                HttpStatus.BAD_REQUEST);
        verifyNoInteractions(quoteCalculator, quoteRepository);
    }

    @Test
    @DisplayName("BC-13: null scopeと空productKeyはfail-closedで400にする")
    void quote_nullScopeと空productKey_入力不備を拒否する() {
        assertBusinessError(
                () -> service.create(ACTOR_ID,
                        new CreateBillingQuoteRequest(null, null, "PLAN", " "),
                        "00000000-0000-0000-0000-000000000003"),
                EntitlementErrorCode.INVALID_SCOPE_KIND,
                HttpStatus.BAD_REQUEST);
        verifyNoInteractions(quoteCalculator, quoteRepository);
    }

    @ParameterizedTest(name = "now={0}")
    @ValueSource(strings = {
            "2028-02-29T14:28:59Z",
            "2028-12-31T14:28:59Z",
            "2029-01-31T14:28:59Z"
    })
    @DisplayName("BC-03/13: JST翌月初まで30分+60秒未満なら022と翌月availableAtを返す")
    void quote_JST月境界の禁止窓_409とavailableAtを返す(String now) {
        service = new BillingQuoteService(Clock.fixed(Instant.parse(now), JST),
                scopeGuard, quoteRepository, quoteCalculator);

        assertThatThrownBy(() -> service.create(ACTOR_ID, request(EntitlementScopeKind.USER, ACTOR_ID, "PRO"),
                "00000000-0000-0000-0000-000000000004"))
                .isInstanceOf(BillingConflictException.class)
                .satisfies(error -> {
                    BillingConflictException conflict = (BillingConflictException) error;
                    assertThat(conflict.getErrorCode()).isEqualTo(EntitlementErrorCode.MONTH_BOUNDARY);
                    assertThat(conflict.getDetails().reason())
                            .isEqualTo(BillingConflictException.Reason.MONTH_BOUNDARY);
                    assertThat(conflict.getDetails().availableAt().atZone(JST).getDayOfMonth()).isEqualTo(1);
                    assertThat(new GlobalExceptionHandler(new StaticMessageSource())
                            .handleBusinessException(conflict).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(quoteCalculator, quoteRepository);
    }

    @ParameterizedTest(name = "now={0}")
    @ValueSource(strings = {
            "2028-02-29T14:28:00Z",
            "2028-12-31T14:28:00Z",
            "2029-01-31T15:00:00Z"
    })
    @DisplayName("BC-03/13: 閏年・年跨ぎ・月初直後を固定Clockで正しく扱う")
    void quote_境界外の閏年年跨ぎ月初_見積り計算へ進む(String now) {
        Clock boundaryClock = Clock.fixed(Instant.parse(now), JST);
        service = new BillingQuoteService(boundaryClock, scopeGuard, quoteRepository, quoteCalculator);
        CreateBillingQuoteRequest request = request(EntitlementScopeKind.USER, ACTOR_ID, "PRO");
        BillingQuoteSnapshot calculated = snapshot(boundaryClock.instant().plus(Duration.ofMinutes(10)));
        given(quoteCalculator.calculate(ACTOR_ID, request, boundaryClock.instant())).willReturn(calculated);
        given(quoteRepository.save(calculated)).willReturn(calculated);

        service.create(ACTOR_ID, request, "00000000-0000-0000-0000-000000000005");

        verify(scopeGuard).check(ACTOR_ID, EntitlementScopeKind.USER, ACTOR_ID);
        verify(quoteRepository).save(calculated);
    }

    private CreateBillingQuoteRequest request(EntitlementScopeKind scopeKind, long scopeId, String productKey) {
        return new CreateBillingQuoteRequest(scopeKind, scopeId, "PLAN", productKey);
    }

    private BillingQuoteSnapshot snapshot(Instant expiresAt) {
        BillingMoney initial = new BillingMoney("JPY", 1000, 909, 91, "消費税", 1000);
        BillingMoney next = new BillingMoney("JPY", 3000, 2727, 273, "消費税", 1000);
        return new BillingQuoteSnapshot(QUOTE_ID, ACTOR_ID, EntitlementScopeKind.TEAM, SCOPE_ID,
                CUSTOMER_ID, BillingProductKind.PLAN, "PRO", BAND_ID, "price_existing_pro_21",
                21, initial, next, "{\"rateBasisPoints\":1000}",
                Instant.parse("2028-02-01T00:00:00Z"), Instant.parse("2028-03-01T00:00:00Z"),
                clock.instant(), null, "a".repeat(64), expiresAt, null, 0L);
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
