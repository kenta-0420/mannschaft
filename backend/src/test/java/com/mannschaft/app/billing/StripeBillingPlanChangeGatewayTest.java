package com.mannschaft.app.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

/**
 * Billing Center PR6b-1 第14隊: {@link StripeBillingPlanChangeGateway} の純UT。
 *
 * <h2>なぜこのクラスが要るか</h2>
 * <p>{@link BillingPlanChangeGateway} は試練Aが置いた発注書であり、<b>本番実装が1つも無いまま</b>
 * 3つの {@code @Service} がコンストラクタ必須注入していた。IT は {@code @MockitoBean} で
 * ポートを覆うため、この欠落は「本番の ApplicationContext が起動できない」という形でしか現れず、
 * Docker の無いローカルでは一度も観測されなかった。本クラスは
 * (1) 実装クラスが Bean として実在すること（＝コンテキストが充足できること）と、
 * (2) Stripe へ渡すパラメータ・Stripe から受け取る値の写像を、Stripe を呼ばずに固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR6b-1 第14隊: Stripe プラン変更ゲートウェイ")
class StripeBillingPlanChangeGatewayTest {

    private static final Instant NOW = Instant.parse("2026-09-17T03:00:00Z");
    private static final String SUB_REF = "sub_test_pr6b1";
    private static final String TARGET_PRICE_REF = "price_target_pr6b1";

    @Mock
    private StripePaymentProvider stripePaymentProvider;

    @Captor
    private ArgumentCaptor<Map<String, String>> metadataCaptor;

    private StripeBillingPlanChangeGateway gateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        gateway = new StripeBillingPlanChangeGateway(
                stripePaymentProvider, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ═════════ コンテキスト充足（欠落の再発防止） ═════════

    @Test
    @DisplayName("BillingPlanChangeGateway の本番実装が @Service として実在する")
    void 本番実装がBeanとして実在する() {
        assertThat(BillingPlanChangeGateway.class)
                .as("ポートに本番実装が無いと、必須注入している3つの @Service が起動できない")
                .isAssignableFrom(StripeBillingPlanChangeGateway.class);
        assertThat(StripeBillingPlanChangeGateway.class.isAnnotationPresent(Service.class))
                .as("component scan に拾われる必要がある")
                .isTrue();
    }

    // ═════════ AC-2: 金額の唯一の出所は Stripe ═════════

    @Test
    @DisplayName("AC-2: 見積りは Stripe が返した金額をそのまま運ぶ（こちらで日割りしない）")
    void 見積りはStripeの値をそのまま運ぶ() {
        given(stripePaymentProvider.previewSubscriptionPlanChange(
                eq(SUB_REF), eq(TARGET_PRICE_REF), eq(3L),
                eq(BillingPlanChangeGateway.PRORATION_BEHAVIOR_ALWAYS_INVOICE)))
                .willReturn(new StripePaymentProvider.InvoicePreviewInfo(
                        "jpy", 1234L, 1122L, 112L, "消費税", new BigDecimal("10.00"),
                        1789000000L, 1791592000L));

        BillingPlanChangeGateway.PlanChangeQuote quote = gateway.previewPlanChange(
                new BillingPlanChangeGateway.PlanChangePreviewCommand(SUB_REF, TARGET_PRICE_REF, 3));

        assertThat(quote.currency()).isEqualTo("jpy");
        assertThat(quote.amountDueNow()).isEqualTo(1234L);
        assertThat(quote.amountExcludingTax()).isEqualTo(1122L);
        assertThat(quote.taxAmount()).isEqualTo(112L);
        assertThat(quote.taxName()).isEqualTo("消費税");
        assertThat(quote.taxRateBasisPoints()).isEqualTo(1000);
        assertThat(quote.periodStart()).isEqualTo(Instant.ofEpochSecond(1789000000L));
        assertThat(quote.periodEnd()).isEqualTo(Instant.ofEpochSecond(1791592000L));
        assertThat(quote.prorationAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("見積りで税が取れない場合も 0 と null で運び、税込から逆算しない")
    void 見積りの税が不明なら逆算しない() {
        given(stripePaymentProvider.previewSubscriptionPlanChange(any(), any(), isNull(), any()))
                .willReturn(new StripePaymentProvider.InvoicePreviewInfo(
                        "jpy", 500L, null, null, null, null, null, null));

        BillingPlanChangeGateway.PlanChangeQuote quote = gateway.previewPlanChange(
                new BillingPlanChangeGateway.PlanChangePreviewCommand(SUB_REF, TARGET_PRICE_REF, null));

        assertThat(quote.amountDueNow()).isEqualTo(500L);
        assertThat(quote.amountExcludingTax()).isZero();
        assertThat(quote.taxAmount()).isZero();
        assertThat(quote.taxName()).isNull();
        assertThat(quote.taxRateBasisPoints()).isNull();
        assertThat(quote.periodStart()).isNull();
        assertThat(quote.periodEnd()).isNull();
    }

    // ═════════ AC-29/AC-30/AC-31: 適用時の Stripe パラメータ ═════════

    @Test
    @DisplayName("AC-29/30/31: always_invoice・pending_if_incomplete・冪等キー・operationId metadata で呼ぶ")
    void 適用はalwaysInvoiceとpendingIfIncompleteで呼ばれる() {
        UUID operationId = UUID.randomUUID();
        String idempotencyKey = BillingContractOperationSagaService.stripeIdempotencyKeyOf(operationId);
        given(stripePaymentProvider.changeSubscriptionPlan(
                any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new StripePaymentProvider.SubscriptionPlanChangeInfo(
                        SUB_REF, "active", "in_diff", "paid", false, null, List.of(), 1789000000L));

        gateway.applyPlanChange(new BillingPlanChangeGateway.PlanChangeApplyCommand(
                SUB_REF, TARGET_PRICE_REF, 5, operationId, idempotencyKey,
                BillingPlanChangeGateway.PRORATION_BEHAVIOR_ALWAYS_INVOICE,
                BillingPlanChangeGateway.PAYMENT_BEHAVIOR_PENDING_IF_INCOMPLETE,
                Map.of(BillingPlanChangeGateway.METADATA_OPERATION_ID_KEY, operationId.toString())));

        org.mockito.Mockito.verify(stripePaymentProvider).changeSubscriptionPlan(
                eq(SUB_REF), eq(TARGET_PRICE_REF), eq(5L),
                eq("always_invoice"), eq("pending_if_incomplete"),
                metadataCaptor.capture(), eq("billing-operation-" + operationId));

        assertThat(metadataCaptor.getValue())
                .as("AC-31: 回収が痕跡照合に使うキーで operationId を焼き付ける")
                .containsEntry(BillingContractOperationRecoveryService.STRIPE_METADATA_OPERATION_ID_KEY,
                        operationId.toString());
        assertThat(idempotencyKey).isEqualTo("billing-operation-" + operationId);
    }

    @Test
    @DisplayName("E6'/AC-32: 同期成功（pending_update なし）では効力発生時刻が立ち、スナップショットは残らない")
    void 同期成功ではpendingUpdateを作らない() {
        given(stripePaymentProvider.changeSubscriptionPlan(
                any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new StripePaymentProvider.SubscriptionPlanChangeInfo(
                        SUB_REF, "active", "in_diff", "paid", false, null, List.of(), 1789000000L));

        BillingPlanChangeGateway.PlanChangeApplyResult result = applyWith(UUID.randomUUID());

        assertThat(result.pendingUpdatePresent()).isFalse();
        assertThat(result.pendingUpdateExpiresAt()).isNull();
        assertThat(result.pendingUpdateTargetSnapshot()).isNull();
        assertThat(result.invoiceRef()).isEqualTo("in_diff");
        assertThat(result.invoiceStatus()).isEqualTo("paid");
        assertThat(result.effectiveAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("E6'/AC-33/AC-79: 3DS 要求（pending_update あり）では期限と照合用スナップショットを運ぶ")
    void 追加認証が要るときはpendingUpdateを運ぶ() throws Exception {
        given(stripePaymentProvider.changeSubscriptionPlan(
                any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new StripePaymentProvider.SubscriptionPlanChangeInfo(
                        SUB_REF, "active", "in_diff", "open", true, 1789003600L,
                        List.of(new StripePaymentProvider.SubscriptionItemDetail(
                                "si_1", TARGET_PRICE_REF, 5L)),
                        1789000000L));

        BillingPlanChangeGateway.PlanChangeApplyResult result = applyWith(UUID.randomUUID());

        assertThat(result.pendingUpdatePresent()).isTrue();
        assertThat(result.pendingUpdateExpiresAt()).isEqualTo(Instant.ofEpochSecond(1789003600L));
        assertThat(result.effectiveAt())
                .as("まだ適用されていない以上、効力発生時刻を捏造しない")
                .isNull();

        JsonNode snapshot = objectMapper.readTree(result.pendingUpdateTargetSnapshot());
        assertThat(snapshot.path("items").get(0).path("price").asText())
                .as("E2'/AC-79: 確定時に現在 items と突き合わせる target Price ref")
                .isEqualTo(TARGET_PRICE_REF);
        assertThat(snapshot.path("items").get(0).path("id").asText()).isEqualTo("si_1");
        assertThat(snapshot.path("items").get(0).path("quantity").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("AC-79: スナップショットは StripeBillingPayloadParser が読める形で書かれる")
    void スナップショットは確定側のパーサが読める() {
        given(stripePaymentProvider.changeSubscriptionPlan(
                any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new StripePaymentProvider.SubscriptionPlanChangeInfo(
                        SUB_REF, "active", "in_diff", "open", true, 1789003600L,
                        List.of(new StripePaymentProvider.SubscriptionItemDetail(
                                "si_1", TARGET_PRICE_REF, 1L)),
                        1789000000L));

        BillingPlanChangeGateway.PlanChangeApplyResult result = applyWith(UUID.randomUUID());

        String parsed = new com.mannschaft.app.billing.invoice.StripeBillingPayloadParser(objectMapper)
                .targetPriceRefFromSnapshot(result.pendingUpdateTargetSnapshot());
        assertThat(parsed).isEqualTo(TARGET_PRICE_REF);
    }

    // ═════════ AC-48/AC-54: 3DS の client secret ═════════

    @Test
    @DisplayName("AC-48/54: client secret は Stripe から都度取得した値をそのまま運ぶ")
    void 追加認証情報は都度取得したものを運ぶ() {
        given(stripePaymentProvider.retrieveSubscriptionPaymentAction(SUB_REF, "in_diff"))
                .willReturn(Optional.of(new StripePaymentProvider.SubscriptionPaymentActionInfo(
                        "payment_intent", "pi_x_secret_y", 1789003600L)));

        Optional<BillingPlanChangeGateway.PaymentAction> action =
                gateway.retrievePaymentAction(SUB_REF, "in_diff");

        assertThat(action).isPresent();
        assertThat(action.get().type()).isEqualTo("payment_intent");
        assertThat(action.get().clientSecret()).isEqualTo("pi_x_secret_y");
        assertThat(action.get().expiresAt()).isEqualTo(Instant.ofEpochSecond(1789003600L));
    }

    @Test
    @DisplayName("追加認証の余地が無ければ空を返す（呼び出し側が 409 にできる）")
    void 追加認証が不要なら空を返す() {
        given(stripePaymentProvider.retrieveSubscriptionPaymentAction(SUB_REF, null))
                .willReturn(Optional.empty());

        assertThat(gateway.retrievePaymentAction(SUB_REF, null)).isEmpty();
    }

    /**
     * 適用要求の定型部分。
     *
     * @param operationId operation の ID
     * @return Stripe 適用結果
     */
    private BillingPlanChangeGateway.PlanChangeApplyResult applyWith(UUID operationId) {
        return gateway.applyPlanChange(new BillingPlanChangeGateway.PlanChangeApplyCommand(
                SUB_REF, TARGET_PRICE_REF, 5, operationId,
                BillingContractOperationSagaService.stripeIdempotencyKeyOf(operationId),
                BillingPlanChangeGateway.PRORATION_BEHAVIOR_ALWAYS_INVOICE,
                BillingPlanChangeGateway.PAYMENT_BEHAVIOR_PENDING_IF_INCOMPLETE,
                Map.of(BillingPlanChangeGateway.METADATA_OPERATION_ID_KEY, operationId.toString())));
    }
}
