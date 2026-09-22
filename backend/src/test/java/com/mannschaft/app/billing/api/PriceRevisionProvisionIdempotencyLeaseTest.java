package com.mannschaft.app.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 試練隊（第2陣）I群（冪等性）: provision / retry-provision / reconcile-provision に限った
 * lease 9分への引き上げ（第6版・重大3対応・AC-132・AC-104）。
 *
 * <p>{@link BillingDurableIdempotencyService} は既存の {@code BillingPlanChangeController}
 * 等が使う耐久冪等台帳（PR4・BC-23）であり、{@code LEASE_DURATION} は現状 2分固定である
 * （{@code begin} に lease 期間を指定する経路が無い）。決定9改訂は
 * 「provision/retry-provision/reconcile-provision の3エンドポイントに限り lease 9分、それ以外は
 * 既定2分」を要求するため、{@code begin} に lease 期間を明示できる新しいオーバーロードが必要になる
 * （本テストが発注書）。同一パッケージ内の package-private クラスを直接テストする
 * （{@code BillingDurableIdempotencyRecoveryTest} と同じ手法）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("試練I群: provision系エンドポイントの9分lease（AC-132/AC-104）")
class PriceRevisionProvisionIdempotencyLeaseTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final Duration PROVISION_LEASE_DURATION = Duration.ofMinutes(9);

    @Mock private BillingApiIdempotencyRepository repository;

    private BillingDurableIdempotencyService service(Clock clock) {
        return new BillingDurableIdempotencyService(clock, repository);
    }

    @Test
    @DisplayName("AC-132: provision/retry-provision/reconcile-provisionはbegin()へ9分leaseを明示できる")
    void beginAcceptsExplicitNineMinuteLeaseForProvisionEndpoints() {
        BillingDurableIdempotencyService idempotencyService = service(Clock.fixed(NOW, ZoneOffset.UTC));
        given(repository.find(1L, "POST", "/price-revisions/x/provision", "key-1")).willReturn(Optional.empty());
        given(repository.reserve(any())).willAnswer(inv -> inv.getArgument(0));

        idempotencyService.begin(1L, "POST", "/price-revisions/x/provision", "key-1", "hash-1",
                "owner-1", PROVISION_LEASE_DURATION);

        var captor = org.mockito.ArgumentCaptor.forClass(BillingIdempotencyRecord.class);
        verify(repository).reserve(captor.capture());
        assertThat(captor.getValue().leaseExpiresAt()).isEqualTo(NOW.plus(PROVISION_LEASE_DURATION));
    }

    @Test
    @DisplayName("AC-104: 9分lease保持中に同一Idempotency-Keyで再送するとPROCESSING(Retry-After付き)になり、6分経過時点でも二重実行されない")
    void concurrentResendWithinNineMinuteLeaseStaysProcessing() {
        Instant reserveAt = NOW;
        Instant sixMinutesLater = NOW.plus(Duration.ofMinutes(6));
        BillingIdempotencyRecord processing = new BillingIdempotencyRecord(
                java.util.UUID.randomUUID(), 1L, "POST", "/price-revisions/x/provision", "key-1", "hash-1",
                BillingIdempotencyStatus.PROCESSING, null, null, "owner-1",
                reserveAt.plus(PROVISION_LEASE_DURATION), reserveAt, null, reserveAt.plus(Duration.ofHours(24)));
        given(repository.find(1L, "POST", "/price-revisions/x/provision", "key-1"))
                .willReturn(Optional.of(processing));

        BillingIdempotencyDecision decision = service(Clock.fixed(sixMinutesLater, ZoneOffset.UTC))
                .begin(1L, "POST", "/price-revisions/x/provision", "key-1", "hash-1", "owner-2",
                        PROVISION_LEASE_DURATION);

        assertThat(decision.kind()).isEqualTo(BillingIdempotencyDecisionKind.PROCESSING);
        assertThat(decision.retryAfterSeconds())
                .as("9分leaseのうち残り3分(180秒)前後が返る（旧6分lease前提の120秒固定ではない）")
                .isGreaterThan(60L);
    }

    @Test
    @DisplayName("AC-132: create/activateは既定の2分leaseのまま（レガシーbegin()を使う限り変更されない）")
    void createAndActivateKeepDefaultTwoMinuteLease() {
        BillingDurableIdempotencyService idempotencyService = service(Clock.fixed(NOW, ZoneOffset.UTC));
        given(repository.find(1L, "POST", "/price-revisions", "key-2")).willReturn(Optional.empty());
        given(repository.reserve(any())).willAnswer(inv -> inv.getArgument(0));

        idempotencyService.begin(1L, "POST", "/price-revisions", "key-2", "hash-2", "owner-2");

        var captor = org.mockito.ArgumentCaptor.forClass(BillingIdempotencyRecord.class);
        verify(repository).reserve(captor.capture());
        assertThat(captor.getValue().leaseExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
    }
}
