package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingContractOperationRepository;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.ContractStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6a — 解約の冪等（AC-28 / AC-29 / AC-30 / AC-31 / AC-31b / AC-32）の受け入れテスト（試練・red）。
 *
 * <h2>第6隊・第7隊への発注書（冪等台帳への束縛の仕方）</h2>
 * <p>新エンドポイントは既存の {@code BillingDurableIdempotencyService} をそのまま使い、
 * {@code BillingCheckoutController#idempotent} と<b>同一の流儀</b>で束縛する。本 IT は
 * PROCESSING 検体を台帳へ直接仕込むため、次の3点を仕様として固定する。</p>
 * <ul>
 *   <li>{@code httpMethod} = {@code "POST"}（解約）／{@code "DELETE"}（撤回）</li>
 *   <li>{@code requestPath} = 具体的な URI（{@code /api/v1/me/billing/contracts/{実UUID}/cancel}）。
 *       テンプレートではなく実 ID を含める（契約ごとに台帳が分かれる）</li>
 *   <li>{@code requestHash} = SHA-256 hex64 の
 *       {@code join("\n", actorId, httpMethod, requestPath, objectMapper.writeValueAsString(request))}。
 *       request DTO は {@code record BillingCancelRequest(Long version)} とし、JSON は
 *       {@code {"version":0}} の1フィールドになる</li>
 * </ul>
 *
 * <p><b>AC-31 と AC-31b を分ける理由</b>: 既存 {@code BillingDurableIdempotencyService#fail} は
 * 応答本文を保存しない（:109-127）。したがって同じキーの再送は replay できず 409 で固定される。
 * これは「詰み」ではなく「そのキーは死んだ」という意味であり、利用者は<b>新しいキー</b>で
 * やり直せなければならない。この2つは別々のテストで測る（AC 側で明示的に分割された）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a 解約の冪等（AC-28〜32・試練 red）")
class BillingContractCancelIdempotencyRedIT extends AbstractBillingCancelResumeApiIT {

    private static final String SUB_REF = "sub_pr6a_idem";

    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private BillingApiIdempotencyRepository idempotencyRepository;

    private Long userId;
    private LocalDateTime periodEnd;
    private UUID contractId;

    @BeforeEach
    void setUp() {
        userId = insertUser("idem");
        periodEnd = LocalDateTime.now(clock).plusDays(18).withNano(0);
        contractId = insertContract(userId, ContractStatus.ACTIVE, PRICE_JPY, SUB_REF, periodEnd, null);
        stubStripeSubscription(SUB_REF, false, periodEnd);
    }

    @AfterEach
    void tearDown() {
        cleanupScope(userId);
    }

    // ═════════ AC-28: 同一キーの再送 ═════════

    @Test
    @DisplayName("AC-28: 同一Idempotency-Keyの再送で同一レスポンスを返しStripe呼び出しは1回だけ")
    void AC28_同一キー再送は同一レスポンスでStripeは1回() throws Exception {
        String key = newKey();

        MvcResult first = cancel(userId, contractId, 0L, key).andExpect(status().isOk()).andReturn();
        MvcResult second = cancel(userId, contractId, 0L, key).andExpect(status().isOk()).andReturn();

        JsonNode a = body(first).path("data");
        JsonNode b = body(second).path("data");
        assertThat(b).as("replay は保存済み応答をそのまま返す").isEqualTo(a);
        assertThat(stripeCallsFor("cancelAtPeriodEnd", SUB_REF))
                .as("Stripe への変更系呼び出しは1回だけ").isEqualTo(1L);
        assertThat(operationRepository
                .findByContractIdAndStatusAndDeletedAtIsNull(contractId, BillingOperationStatus.APPLIED))
                .as("operation も1件だけ（再送で二重起票しない）").hasSize(1);
    }

    // ═════════ AC-29: 同一キー・異 request hash ═════════

    @Test
    @DisplayName("AC-29: 同一Idempotency-Keyで異なるrequest hashは409 ENTITLEMENT_021")
    void AC29_同一キーで別内容は409() throws Exception {
        String key = newKey();
        cancel(userId, contractId, 0L, key).andExpect(status().isOk());

        // version だけ変えた別内容（本文が違えば hash が違う）。
        cancel(userId, contractId, 1L, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));
    }

    // ═════════ AC-30: PROCESSING 中の同一キー ═════════

    @Test
    @DisplayName("AC-30: 先行要求がPROCESSINGの間の同一キー再送は409＋Retry-Afterで本処理を走らせない")
    void AC30_PROCESSING中は409とRetryAfter() throws Exception {
        String key = newKey();
        String path = String.format(CANCEL_PATH, contractId);
        seedProcessingLedger(userId, "POST", path, key, requestHash(userId, "POST", path, 0L));

        cancel(userId, contractId, 0L, key)
                .andExpect(status().isConflict())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));

        assertThat(reloadContract(contractId).getCancelledAt())
                .as("PROCESSING 中は本処理を走らせない").isNull();
        assertThat(stripeCalls("cancelAtPeriodEnd")).isZero();
    }

    // ═════════ AC-31 / AC-31b: Stripe 失敗後 ═════════

    @Test
    @DisplayName("AC-31: Stripe失敗後に同じIdempotency-Keyで再送すると409 ENTITLEMENT_021（本文が無くreplayできない）")
    void AC31_失敗後の同一キー再送は409() throws Exception {
        String key = newKey();
        willThrow(new IllegalStateException("stripe down"))
                .given(billingPaymentGateway).cancelAtPeriodEnd(anyString());

        cancel(userId, contractId, 0L, key).andExpect(status().isBadGateway());

        cancel(userId, contractId, 0L, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"));
    }

    @Test
    @DisplayName("AC-31b: Stripe失敗後でも新しいIdempotency-Keyでの再解約は成功する（pointerが解放済み）")
    void AC31b_失敗後に新しいキーならやり直せる() throws Exception {
        willThrow(new IllegalStateException("stripe down"))
                .given(billingPaymentGateway).cancelAtPeriodEnd(anyString());
        cancel(userId, contractId, 0L, newKey()).andExpect(status().isBadGateway());

        // Stripe を回復させ、別のキーでやり直す。
        org.mockito.Mockito.reset(billingPaymentGateway);
        stubStripeSubscription(SUB_REF, false, periodEnd);

        cancel(userId, contractId, 0L, newKey())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));

        assertThat(reloadContract(contractId).getCancelledAt())
                .as("やり直しが実際に効く（409 固定で詰まない）").isNotNull();
    }

    // ═════════ AC-32: operation.idempotency_key は operationId ═════════

    @Test
    @DisplayName("AC-32: operation表のidempotency_keyはoperationId（UUID36文字）でありHTTPヘッダ値ではない")
    void AC32_operationのキーはoperationId() throws Exception {
        String headerKey = "my-cancel-key-0001";

        cancel(userId, contractId, 0L, headerKey).andExpect(status().isOk());

        List<BillingContractOperationEntity> ops = operationRepository
                .findByContractIdAndStatusAndDeletedAtIsNull(contractId, BillingOperationStatus.APPLIED);
        assertThat(ops).hasSize(1);
        BillingContractOperationEntity op = ops.get(0);
        assertThat(op.getIdempotencyKey())
                .as("HTTP ヘッダ値をそのまま入れてはならない").isNotEqualTo(headerKey);
        assertThat(op.getIdempotencyKey()).as("CHAR(36) に収まる UUID 表記").hasSize(36);
        assertThat(UUID.fromString(op.getIdempotencyKey()))
                .as("格納するのは operation 自身の id").isEqualTo(op.getId());
    }

    @Test
    @DisplayName("AC-32: 36文字を超えるIdempotency-Keyヘッダでも5xxにせず4xxで拒否する（そのまま格納しない）")
    void AC32_長すぎるキーは4xxで拒否() throws Exception {
        String tooLong = "x".repeat(80);

        int statusCode = cancel(userId, contractId, 0L, tooLong).andReturn().getResponse().getStatus();

        assertThat(statusCode)
                .as("CHAR(36) 列へ素通しして DataIntegrityViolation で 500 になってはならない")
                .isBetween(400, 499);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /** PROCESSING かつ lease 有効の冪等レコードを台帳へ直に置く（AC-30 の検体）。 */
    private void seedProcessingLedger(long actorId, String method, String path,
                                      String key, String hash) {
        Instant now = clock.instant();
        idempotencyRepository.reserve(new BillingIdempotencyRecord(
                null, actorId, method, path, key, hash,
                BillingIdempotencyStatus.PROCESSING, null, null, "other-worker-" + UUID.randomUUID(),
                now.plus(Duration.ofMinutes(2)), now, null, now.plus(Duration.ofHours(24))));
    }

    /** {@code BillingCheckoutController#requestHash} と同一の算式（発注書の固定点）。 */
    private String requestHash(long actorId, String method, String path, long version) throws Exception {
        String canonical = String.join("\n", String.valueOf(actorId), method, path,
                "{\"version\":" + version + "}");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}
