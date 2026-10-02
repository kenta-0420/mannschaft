package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** BC-23: actor/method/path/key と request hash に束縛した耐久冪等性の試練。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR4 durable idempotency 試練")
class BillingIdempotencyServiceTrialTest {
    private static final long ACTOR_ID = 7L;
    private static final String METHOD = "POST";
    private static final String PATH = "/api/v1/me/billing/checkout-sessions";
    private static final String KEY = "00000000-0000-0000-0000-000000000201";
    private static final String HASH = "a".repeat(64);
    private static final UUID RECORD_ID = UUID.fromString("01999d74-5130-7000-8000-000000000020");

    @Mock private BillingApiIdempotencyRepository repository;

    private Clock clock;
    private BillingDurableIdempotencyService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2028-02-10T03:00:00Z"), ZoneOffset.UTC);
        service = new BillingDurableIdempotencyService(clock, repository);
    }

    @Test
    @DisplayName("BC-23: 同一key同一hashのSUCCEEDEDは保存済みstatus/bodyをreplayする")
    void begin_同一hash成功済み_保存済みresponseを返す() {
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY)).willReturn(Optional.of(
                record(HASH, BillingIdempotencyStatus.SUCCEEDED, "owner-a",
                        clock.instant().minusSeconds(1), 201, "{\"data\":{\"checkoutUrl\":\"https://safe.example\"}}")));

        BillingIdempotencyDecision decision = service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b");

        assertThat(decision.kind()).isEqualTo(BillingIdempotencyDecisionKind.REPLAY);
        assertThat(decision.responseStatus()).isEqualTo(201);
        assertThat(decision.responseJson()).contains("checkoutUrl");
        verify(repository, never()).reserve(any());
    }

    @Test
    @DisplayName("BC-23: 同一keyで別hashなら021/409とし既存応答を漏らさない")
    void begin_別hash_409を返す() {
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY)).willReturn(Optional.of(
                record("b".repeat(64), BillingIdempotencyStatus.SUCCEEDED, "owner-a",
                        clock.instant().minusSeconds(1), 201, "{\"secret\":\"must-not-leak\"}")));

        assertThatThrownBy(() -> service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException exception = (BusinessException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT);
                    assertThat(new GlobalExceptionHandler(new StaticMessageSource())
                            .handleBusinessException(exception).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).doesNotContain("must-not-leak");
                });
    }

    @Test
    @DisplayName("BC-23: PROCESSINGかつlease有効ならStripe所有権を渡さずRetry-After秒を返す")
    void begin_有効lease中_PROCESSINGとRetryAfterを返す() {
        Instant leaseExpiry = clock.instant().plusSeconds(37);
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY)).willReturn(Optional.of(
                record(HASH, BillingIdempotencyStatus.PROCESSING, "owner-a", leaseExpiry, null, null)));

        BillingIdempotencyDecision decision = service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b");

        assertThat(decision.kind()).isEqualTo(BillingIdempotencyDecisionKind.PROCESSING);
        assertThat(decision.retryAfterSeconds()).isEqualTo(37L);
        verify(repository, never()).recoverStaleLease(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("BC-23: stale leaseはworkerだけが観測owner/expiry条件付きCASで回収する")
    void recoverStale_観測したownerとexpiryをCAS条件にする() {
        Instant observedExpiry = clock.instant().minusSeconds(1);
        given(repository.recoverStaleLease(eq(RECORD_ID), eq("owner-a"), eq(observedExpiry),
                eq("recovery-worker-2"), any(), eq(clock.instant()))).willReturn(1);

        service.recoverStale(RECORD_ID, "owner-a", observedExpiry, "recovery-worker-2");

        verify(repository).recoverStaleLease(eq(RECORD_ID), eq("owner-a"), eq(observedExpiry),
                eq("recovery-worker-2"), eq(clock.instant().plus(Duration.ofMinutes(2))), eq(clock.instant()));
    }

    @Test
    @DisplayName("BC-23: lease ownerが変わったCAS失敗を成功扱いにしない")
    void complete_別ownerのCAS失敗_競合にする() {
        given(repository.completeIfLeaseOwner(RECORD_ID, "owner-b", 201, "{}", clock.instant()))
                .willReturn(0);

        assertThatThrownBy(() -> service.complete(RECORD_ID, "owner-b", 201, "{}"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT);
    }

    private BillingIdempotencyRecord record(String hash, BillingIdempotencyStatus status,
                                             String owner, Instant leaseExpiry,
                                             Integer responseStatus, String responseJson) {
        return new BillingIdempotencyRecord(RECORD_ID, ACTOR_ID, METHOD, PATH, KEY, hash, status,
                responseStatus, responseJson, owner, leaseExpiry,
                clock.instant().minusSeconds(10), status == BillingIdempotencyStatus.PROCESSING
                ? null : clock.instant().minusSeconds(1), clock.instant().plusSeconds(86400));
    }
}
