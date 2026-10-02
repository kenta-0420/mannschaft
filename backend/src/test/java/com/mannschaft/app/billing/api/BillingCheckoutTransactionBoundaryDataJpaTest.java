package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BC-13 / BC-23 の CAS 更新に <b>トランザクション境界が実在する</b> ことの実証。
 *
 * <p><b>なぜ既存の試練では足りないのか</b>: application service の試練は repository を素の mock で
 * 差し替えるため、{@code @Transactional} の有無を<b>原理的に観測できない</b>。実際 PR4 では
 * checkout フローの主要クラスに {@code @Transactional} が 1 つも無く、Spring Data の宣言
 * {@code @Modifying} クエリが本番で {@code TransactionRequiredException} になる状態のまま
 * 試練は全て緑だった（Stripe 課金成功後に DB 更新だけが落ちる欠陥）。</p>
 *
 * <p>そこで本テストは <b>周囲にトランザクションが無い状態</b>（{@link Propagation#NOT_SUPPORTED}）で
 * ポート実装（アダプタ）を直接呼ぶ。アダプタ自身が境界を持たなければここで例外になり、
 * 持っていれば 1 行更新が成立してコミット後の再読込に反映される。
 * 呼び出し元（controller / application service）は Stripe 呼び出しを跨ぐため境界を持てず、
 * 境界はアダプタ側の短いトランザクションに置く、という設計判断をここで固定する。</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({BillingQuoteRepositoryAdapter.class, BillingReturnStateNonceRepositoryAdapter.class,
        BillingApiIdempotencyRepositoryAdapter.class,
        BillingCheckoutTransactionBoundaryDataJpaTest.ObjectMapperConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("PR4 Checkout: CAS 更新のトランザクション境界 DataJpaTest")
class BillingCheckoutTransactionBoundaryDataJpaTest {

    private static final Instant NOW = Instant.parse("2028-02-10T03:00:00Z");
    private static final long ACTOR_ID = 7L;
    private static final long SCOPE_ID = 91L;

    /** {@code @DataJpaTest} は web 系の自動構成を載せないため ObjectMapper を明示供給する。 */
    @TestConfiguration
    static class ObjectMapperConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private BillingQuoteJpaRepository quoteJpaRepository;
    @Autowired private BillingReturnStateNonceJpaRepository nonceJpaRepository;
    @Autowired private BillingApiIdempotencyJpaRepository idempotencyJpaRepository;

    @Autowired private BillingQuoteRepository quoteRepository;
    @Autowired private BillingReturnStateNonceRepository nonceRepository;
    @Autowired private BillingApiIdempotencyRepository idempotencyRepository;

    @Test
    @DisplayName("BC-13: quote の CAS 消費は外側にトランザクションが無くても成立しコミットされる")
    void quote消費_周囲にtxが無くてもコミットされる() {
        BillingQuoteEntity saved = quoteJpaRepository.saveAndFlush(quote());

        int consumed = quoteRepository.consumeIfUnchanged(saved.getId(), ACTOR_ID, 0L, NOW);

        assertThat(consumed).isEqualTo(1);
        BillingQuoteEntity stored = quoteJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(stored.getConsumedAt()).isEqualTo(NOW);
        assertThat(stored.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("BC-28: nonce の CAS 消費は外側にトランザクションが無くても成立しコミットされる")
    void nonce消費_周囲にtxが無くてもコミットされる() {
        String nonceHash = "e".repeat(64);
        nonceRepository.register(nonceHash, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS,
                ACTOR_ID, EntitlementScopeKind.TEAM, SCOPE_ID, NOW.plusSeconds(600));

        int consumed = nonceRepository.consumeIfValid(nonceHash,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, ACTOR_ID,
                EntitlementScopeKind.TEAM, SCOPE_ID, NOW);
        int replay = nonceRepository.consumeIfValid(nonceHash,
                BillingReturnStateService.Purpose.CHECKOUT_SUCCESS, ACTOR_ID,
                EntitlementScopeKind.TEAM, SCOPE_ID, NOW);

        assertThat(consumed).isEqualTo(1);
        assertThat(replay).isZero();
        assertThat(nonceJpaRepository.findAll().get(0).getConsumedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("BC-23: 冪等レコードの予約と complete/fail の CAS が外側 tx 無しで成立しコミットされる")
    void 冪等レコード_予約とCAS確定が周囲にtxが無くても成立する() {
        BillingIdempotencyRecord reserved = idempotencyRepository.reserve(new BillingIdempotencyRecord(
                null, ACTOR_ID, "POST", "/api/v1/me/billing/checkout-sessions",
                "00000000-0000-0000-0000-000000000401", "f".repeat(64),
                BillingIdempotencyStatus.PROCESSING, null, null, "owner-a",
                NOW.plusSeconds(120), NOW, null, NOW.plusSeconds(86400)));

        // 予約はここでコミット済みでなければならない（設計: reservation → commit → Stripe → CAS 確定）。
        assertThat(idempotencyJpaRepository.findById(reserved.id())).isPresent();

        int wrongOwner = idempotencyRepository
                .completeIfLeaseOwner(reserved.id(), "owner-b", 201, "{\"data\":{}}", NOW);
        int completed = idempotencyRepository
                .completeIfLeaseOwner(reserved.id(), "owner-a", 201, "{\"data\":{}}", NOW);

        assertThat(wrongOwner).isZero();
        assertThat(completed).isEqualTo(1);
        BillingApiIdempotencyEntity stored =
                idempotencyJpaRepository.findById(reserved.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BillingIdempotencyStatus.SUCCEEDED);
        assertThat(stored.getResponseStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("BC-23: 失敗確定（FAILED）も外側 tx 無しで成立し、応答本文は保存しない")
    void 冪等レコード_失敗確定が周囲にtxが無くても成立する() {
        BillingIdempotencyRecord reserved = idempotencyRepository.reserve(new BillingIdempotencyRecord(
                null, ACTOR_ID, "POST", "/api/v1/me/billing/quotes",
                "00000000-0000-0000-0000-000000000402", "0".repeat(64),
                BillingIdempotencyStatus.PROCESSING, null, null, "owner-a",
                NOW.plusSeconds(120), NOW, null, NOW.plusSeconds(86400)));

        int failed = idempotencyRepository.failIfLeaseOwner(reserved.id(), "owner-a", null, null, NOW);

        assertThat(failed).isEqualTo(1);
        BillingApiIdempotencyEntity stored =
                idempotencyJpaRepository.findById(reserved.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BillingIdempotencyStatus.FAILED);
        assertThat(stored.getResponseJson()).isNull();
        assertThat(stored.getResponseStatus()).isNull();
    }

    private BillingQuoteEntity quote() {
        return BillingQuoteEntity.builder()
                .actorId(ACTOR_ID)
                .billingCustomerId(UUID.fromString("01999d74-5130-7000-8000-000000000041"))
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(SCOPE_ID)
                .productKind(BillingProductKind.PLAN)
                .productKey("PRO")
                .priceBandVersionId(UUID.fromString("01999d74-5130-7000-8000-000000000042"))
                .memberCount(21)
                .taxSnapshot("{\"rateBasisPoints\":1000}")
                .amountSnapshot("{\"initialTotal\":null,\"nextMonthlyTotal\":null}")
                .periodStart(Instant.parse("2028-02-01T00:00:00Z"))
                .periodEnd(Instant.parse("2028-03-01T00:00:00Z"))
                .prorationAt(NOW)
                .requestHash("a".repeat(64))
                .expiresAt(NOW.plusSeconds(600))
                .version(0L)
                .createdAt(NOW.minusSeconds(10))
                .build();
    }
}
