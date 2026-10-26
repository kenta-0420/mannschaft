package com.mannschaft.app.billing.api;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BC-23 検分指摘の根治確認。
 *
 * <ul>
 *   <li>P1-1: 期限切れ lease が <b>{@code begin} 経由で</b>回収され再実行可能になること
 *       （回収 CAS に負けた場合は横取りしないこと）</li>
 *   <li>P2-1: 同時予約の UNIQUE 競合が 5xx ではなく冪等応答に写ること</li>
 * </ul>
 *
 * <p>ここで測るのは service の分岐であり、CAS 自体の SQL 意味論は
 * {@link BillingApiIdempotencyRepositoryDataJpaTest} が実 DDL で固定している。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR4 durable idempotency 回収・競合の根治確認")
class BillingDurableIdempotencyRecoveryTest {
    private static final long ACTOR_ID = 7L;
    private static final String METHOD = "POST";
    private static final String PATH = "/api/v1/me/billing/checkout-sessions";
    private static final String KEY = "00000000-0000-0000-0000-000000000401";
    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final UUID RECORD_ID = UUID.fromString("01999d74-5130-7000-8000-000000000050");
    private static final Instant NOW = Instant.parse("2028-02-10T03:00:00Z");

    @Mock private BillingApiIdempotencyRepository repository;

    private BillingDurableIdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new BillingDurableIdempotencyService(
                Clock.fixed(NOW, ZoneOffset.UTC), repository);
    }

    // ═════════ P1-1: 期限切れ lease の回収 ═════════

    @Test
    @DisplayName("P1-1: 期限切れleaseはbegin自身がCAS回収し新leaseOwnerでACQUIREDを返す")
    void begin_期限切れlease_回収してACQUIREDを返す() {
        Instant expiredAt = NOW.minusSeconds(1);
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY))
                .willReturn(Optional.of(processing("owner-a", expiredAt)));
        given(repository.recoverStaleLease(eq(RECORD_ID), eq("owner-a"), eq(expiredAt),
                eq("owner-b"), any(), any())).willReturn(1);

        BillingIdempotencyDecision decision =
                service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b");

        assertThat(decision.kind()).isEqualTo(BillingIdempotencyDecisionKind.ACQUIRED);
        // 既存行を再利用するため、complete/fail の CAS 対象 id は既存行の id でなければならない。
        assertThat(decision.id()).isEqualTo(RECORD_ID);
        assertThat(decision.retryAfterSeconds()).isZero();
        verify(repository, never()).reserve(any());
    }

    @Test
    @DisplayName("P1-1: 回収CASに負けたら横取りせずPROCESSINGを返す")
    void begin_回収CAS敗北_横取りせずPROCESSING() {
        Instant expiredAt = NOW.minusSeconds(1);
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY))
                .willReturn(Optional.of(processing("owner-a", expiredAt)));
        given(repository.recoverStaleLease(eq(RECORD_ID), eq("owner-a"), eq(expiredAt),
                eq("owner-b"), any(), any())).willReturn(0);

        BillingIdempotencyDecision decision =
                service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b");

        assertThat(decision.kind()).isEqualTo(BillingIdempotencyDecisionKind.PROCESSING);
        assertThat(decision.retryAfterSeconds()).isZero();
    }

    @Test
    @DisplayName("P1-1: lease有効中は回収を試みない（既存の非横取り契約を維持）")
    void begin_有効lease_回収を試みない() {
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY))
                .willReturn(Optional.of(processing("owner-a", NOW.plusSeconds(90))));

        BillingIdempotencyDecision decision =
                service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b");

        assertThat(decision.kind()).isEqualTo(BillingIdempotencyDecisionKind.PROCESSING);
        assertThat(decision.retryAfterSeconds()).isEqualTo(90L);
        verify(repository, never()).recoverStaleLease(any(), any(), any(), any(), any(), any());
    }

    // ═════════ P2-1: 予約の UNIQUE 競合 ═════════

    @Test
    @DisplayName("P2-1: 同時予約のUNIQUE競合は500ではなくPROCESSING（Retry-After付き）になる")
    void begin_予約競合_PROCESSINGへ写す() {
        Instant leaseExpiry = NOW.plusSeconds(120);
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(processing("owner-a", leaseExpiry)));
        willThrow(new DataIntegrityViolationException("uk_bai_actor_request"))
                .given(repository).reserve(any());

        BillingIdempotencyDecision decision =
                service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b");

        assertThat(decision.kind()).isEqualTo(BillingIdempotencyDecisionKind.PROCESSING);
        assertThat(decision.retryAfterSeconds()).isEqualTo(120L);
    }

    @Test
    @DisplayName("P2-1: 競合相手が既に確定済みならREPLAYとして保存済み応答を返す")
    void begin_予約競合_確定済みならREPLAY() {
        BillingIdempotencyRecord succeeded = new BillingIdempotencyRecord(
                RECORD_ID, ACTOR_ID, METHOD, PATH, KEY, HASH,
                BillingIdempotencyStatus.SUCCEEDED, 201, "{\"data\":{}}", "owner-a",
                null, NOW.minusSeconds(10), NOW, NOW.plusSeconds(86400));
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(succeeded));
        willThrow(new DataIntegrityViolationException("uk_bai_actor_request"))
                .given(repository).reserve(any());

        BillingIdempotencyDecision decision =
                service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b");

        assertThat(decision.kind()).isEqualTo(BillingIdempotencyDecisionKind.REPLAY);
        assertThat(decision.responseStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("P2-1: 競合相手のrequest hashが違えば409（本文は載せない）")
    void begin_予約競合_hash不一致は409() {
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(processing("owner-a", NOW.plusSeconds(120))));
        willThrow(new DataIntegrityViolationException("uk_bai_actor_request"))
                .given(repository).reserve(any());

        assertThatThrownBy(() -> service.begin(ACTOR_ID, METHOD, PATH, KEY, OTHER_HASH, "owner-b"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("P2-1: 一意制約以外の整合性違反（読み直しても行が無い）は握り潰さず送出する")
    void begin_競合でない整合性違反_元の例外を送出する() {
        given(repository.find(ACTOR_ID, METHOD, PATH, KEY)).willReturn(Optional.empty());
        willThrow(new DataIntegrityViolationException("not-null violation"))
                .given(repository).reserve(any());

        assertThatThrownBy(() -> service.begin(ACTOR_ID, METHOD, PATH, KEY, HASH, "owner-b"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("not-null violation");
    }

    private BillingIdempotencyRecord processing(String leaseOwner, Instant leaseExpiresAt) {
        return new BillingIdempotencyRecord(
                RECORD_ID, ACTOR_ID, METHOD, PATH, KEY, HASH,
                BillingIdempotencyStatus.PROCESSING, null, null, leaseOwner,
                leaseExpiresAt, NOW.minusSeconds(300), null, NOW.plusSeconds(86400));
    }
}
