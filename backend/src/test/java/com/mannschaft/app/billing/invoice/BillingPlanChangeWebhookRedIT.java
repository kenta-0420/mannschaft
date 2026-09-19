package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.ActiveBillingContractOperationPointerEntity;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerRepository;
import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingContractOperationRepository;
import com.mannschaft.app.billing.BillingOperationActorKind;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.BillingOperationStep;
import com.mannschaft.app.payment.WebhookProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6b-1 — D群 webhook（AC-72 / AC-74〜87）の受け入れテスト（試練・red）。
 *
 * <p><b>なぜ dispatcher 層で測るのか</b>: AC-74〜76 は「4種が <b>dispatcher から billing の受け口まで
 * 届く</b>」ことを要求している。billing のサービスを直接呼ぶテストでは、
 * {@code StripeWebhookService#PR5_PENDING_EVENT_TYPES} に入ったままで
 * <b>dispatcher が受け口へ渡していない</b>という経路の断線を原理的に検出できない。
 * 座席は実 {@code StripeWebhookController} ＋実署名検証＋実 DB（{@link AbstractBillingInvoiceWebhookIT}）。</p>
 *
 * <p><b>第9隊への発注書</b>:</p>
 * <ul>
 *   <li>{@code invoice.payment_action_required} / {@code customer.subscription.pending_update_applied} /
 *       {@code customer.subscription.pending_update_expired} を PR5 の保留リストから外し、
 *       billing の受け口へ配線する。{@code customer.subscription.*} 系は
 *       {@code MembershipSubscriptionWebhookService#isSubscriptionEvent} の対象外なので、
 *       経路を足さないと末尾の {@code default} へ落ちる（AC-75）。</li>
 *   <li>PR6a が通していた {@code customer.subscription.updated} は、プラン変更の確定にも使う。</li>
 *   <li><b>未対応種別（{@code subscription_schedule.*}＝PR6b-2）を {@code IGNORED} で確定させない</b>
 *       （AC-77）。確定させると event_id が焼かれ、後続 PR が永久に拾えなくなる。</li>
 *   <li>照合は change 行へ<b>保存した</b> {@code pending_update_expires_at} /
 *       {@code pending_update_target_snapshot} と <b>現在の items</b> で行う（E2'・AC-79）。
 *       適用後の Subscription からは live な {@code pending_update} を取得できない。</li>
 *   <li>古い event で現状態を戻さない。判定軸は {@code EventEnvelope#createdEpochSec}（AC-82）。</li>
 * </ul>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 プラン変更 webhook（D群 AC-72/74〜87・試練 red）")
class BillingPlanChangeWebhookRedIT extends AbstractBillingInvoiceWebhookIT {

    /** 適用後の items がこの price になっていれば「切替済み」（AC-79 の照合材料）。 */
    private static final String TARGET_PRICE_REF = "price_full_band1";
    private static final String OLD_PRICE_REF = "price_basic_band1";
    /** change 行へ保存する target スナップショット（E2'）。 */
    private static final String TARGET_SNAPSHOT = "{\"items\":[{\"price\":\"" + TARGET_PRICE_REF + "\"}]}";

    @Autowired private BillingContractChangeRepository changeRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;

    private UUID operationId;
    private UUID changeId;

    @BeforeEach
    void seedPlanChange() {
        jdbcTemplate.update("DELETE FROM billing_contract_changes");
        jdbcTemplate.update("DELETE FROM active_billing_contract_operation_pointers");
        jdbcTemplate.update("DELETE FROM billing_contract_operations");

        BillingContractOperationEntity op = operationRepository.saveAndFlush(
                BillingContractOperationEntity.builder()
                        .contractId(billingContractId)
                        .billingCustomerId(billingCustomerId)
                        .kind(BillingOperationKind.PLAN_CHANGE)
                        .status(BillingOperationStatus.CALLING_STRIPE)
                        .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
                        .idempotencyKey(UUID.randomUUID().toString())
                        .requestHash("0".repeat(64))
                        .stripeSubscriptionRef(BILLING_SUBSCRIPTION_REF)
                        .version(0L)
                        .actorKind(BillingOperationActorKind.USER)
                        .createdBy(BILLING_SCOPE_ID)
                        .build());
        operationId = op.getId();
        pointerRepository.saveAndFlush(ActiveBillingContractOperationPointerEntity.builder()
                .contractId(billingContractId).operationId(operationId).build());
        changeId = insertChange(BillingContractChangeStatus.PENDING_PAYMENT, "in_planchange");
    }

    private UUID insertChange(BillingContractChangeStatus status, String invoiceRef) {
        BillingContractChangeEntity change = changeRepository.saveAndFlush(
                BillingContractChangeEntity.builder()
                        .operationId(operationId)
                        .contractId(billingContractId)
                        .billingCustomerId(billingCustomerId)
                        .kind(BillingContractChangeKind.UPGRADE)
                        .status(status)
                        .fromPlanKey("BASIC")
                        .toPlanKey("FULL")
                        .fromPriceBandVersionId(UUID.randomUUID())
                        .toPriceBandVersionId(UUID.randomUUID())
                        .fromAmountIncludingTax(1_000L)
                        .toAmountIncludingTax(3_000L)
                        .stripeSubscriptionRef(BILLING_SUBSCRIPTION_REF)
                        .stripeInvoiceRef(invoiceRef)
                        .pendingUpdateExpiresAt(Instant.now().plusSeconds(3_600))
                        .pendingUpdateTargetSnapshot(TARGET_SNAPSHOT)
                        .effectiveAt(Instant.now())
                        .idempotencyKey(operationId.toString())
                        .requestHash("0".repeat(64))
                        .version(0L)
                        .createdBy(BILLING_SCOPE_ID)
                        .build());
        return change.getId();
    }

    private BillingContractChangeStatus changeStatus() {
        return changeRepository.findByIdAndDeletedAtIsNull(changeId).orElseThrow().getStatus();
    }

    // ───────────── 検体（payload） ─────────────

    /** 差額請求の invoice（billing 所有の customer / subscription）。 */
    private String upgradeInvoice(String invoiceRef, String status) {
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_" + invoiceRef, "FULL プラン差額", 10L, 2_000L, 0L, 200L, false, 1000);
        return StripeWebhookPayloadFixture.invoiceObject(
                invoiceRef, BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF, status,
                "jpy", 2_000L, 0L, 200L, 2_200L, line);
    }

    /**
     * プラン変更中／適用後の Subscription。
     *
     * @param itemPriceRef      現在の items が指す price（{@code TARGET_PRICE_REF} なら切替済み）
     * @param pendingUpdateJson 存続している {@code pending_update}（null なら無し＝適用済みか失効後）
     */
    private String planSubscription(String itemPriceRef, String pendingUpdateJson) {
        return """
                {"id":"%s","object":"subscription","customer":"%s","status":"active",
                 "current_period_start":1767225600,"current_period_end":1769904000,
                 "cancel_at_period_end":false,"created":1767225600,"livemode":false,
                 "metadata":{"billingOperationId":"%s"},
                 "pending_update":%s,
                 "items":{"object":"list","has_more":false,"url":"/v1/subscription_items",
                   "data":[{"id":"si_planchange","object":"subscription_item",
                            "price":{"id":"%s","object":"price","currency":"jpy","unit_amount":3000}}]}}"""
                .formatted(BILLING_SUBSCRIPTION_REF, BILLING_CUSTOMER_REF, operationId,
                        pendingUpdateJson == null ? "null" : pendingUpdateJson, itemPriceRef);
    }

    private String pendingUpdate(long expiresAtEpochSec) {
        return """
                {"expires_at":%d,"subscription_items":[{"price":"%s"}]}"""
                .formatted(expiresAtEpochSec, TARGET_PRICE_REF);
    }

    // ═════════ AC-74〜76: 4種が受け口まで届く ═════════

    @Test
    @DisplayName("AC-74: invoice.payment_action_required が dispatcher から billing の受け口まで届き change が REQUIRES_ACTION になる")
    void AC74_payment_action_requiredが受け口まで届く() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac74", "invoice.payment_action_required", upgradeInvoice("in_planchange", "open")));

        assertThat(changeStatus())
                .as("PR5 の保留リストに入ったままなら RECEIVED で止まり、ここは PENDING_PAYMENT のまま赤くなる")
                .isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
        assertThat(webhookEvent("evt_ac74").orElseThrow().getProcessStatus())
                .as("受け口が処理したので確定する").isEqualTo(WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("AC-75: customer.subscription.pending_update_applied が受け口まで届く（isSubscriptionEvent の対象外なので経路を足す必要がある）")
    void AC75_pending_update_appliedが受け口まで届く() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac75_paid", "invoice.paid", upgradeInvoice("in_planchange", "paid")));

        postSigned(StripeWebhookPayloadFixture.event("evt_ac75", "customer.subscription.pending_update_applied",
                planSubscription(TARGET_PRICE_REF, null)));

        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.APPLIED);
        assertThat(webhookEvent("evt_ac75").orElseThrow().getProcessStatus())
                .isEqualTo(WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("AC-76: customer.subscription.pending_update_expired が受け口まで届く")
    void AC76_pending_update_expiredが受け口まで届く() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event("evt_ac76", "customer.subscription.pending_update_expired",
                planSubscription(OLD_PRICE_REF, null)));

        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.FAILED);
        assertThat(webhookEvent("evt_ac76").orElseThrow().getProcessStatus())
                .isEqualTo(WebhookProcessStatus.PROCESSED);
    }

    // ═════════ AC-77: 未対応種別を IGNORED で焼かない ═════════

    @Test
    @DisplayName("AC-77: 未対応種別（subscription_schedule.* = PR6b-2）は IGNORED として確定されない")
    void AC77_未対応種別をIGNOREDで確定しない() throws Exception {
        String schedule = """
                {"id":"sub_sched_ac77","object":"subscription_schedule","customer":"%s",
                 "subscription":"%s","status":"active","livemode":false}"""
                .formatted(BILLING_CUSTOMER_REF, BILLING_SUBSCRIPTION_REF);

        int status = postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac77", "subscription_schedule.updated", schedule)).getResponse().getStatus();

        assertThat(status).as("Stripe の再送は止める（200）").isEqualTo(200);
        assertThat(webhookEvent("evt_ac77").orElseThrow().getProcessStatus())
                .as("default -> IGNORED は event_id を焼き、PR6b-2 が永久に拾えなくなる")
                .isEqualTo(WebhookProcessStatus.RECEIVED);
    }

    // ═════════ AC-78 / AC-79: 二重照合と E2' の照合材料 ═════════

    @Test
    @DisplayName("AC-78: REQUIRES_ACTION への遷移は invoice/PI・pending update・contract/customer を二重照合してから行う")
    void AC78_二重照合してからREQUIRES_ACTIONにする() throws Exception {
        // customer が別人の invoice（subscription だけ一致）では所有と断定できず、change を動かさない。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac78", "FULL プラン差額", 10L, 2_000L, 0L, 200L, false, 1000);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac78_mismatch", "invoice.payment_action_required",
                StripeWebhookPayloadFixture.invoiceObject("in_ac78", "cus_someone_else",
                        BILLING_SUBSCRIPTION_REF, "open", "jpy", 2_000L, 0L, 200L, 2_200L, line)));

        assertThat(changeStatus())
                .as("customer が一致しない検体で change を進めない")
                .isEqualTo(BillingContractChangeStatus.PENDING_PAYMENT);

        // 陽性対照: customer も subscription も一致すれば進む。
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac78_match", "invoice.payment_action_required", upgradeInvoice("in_planchange", "open")));
        assertThat(changeStatus())
                .as("陽性対照: 二重照合が通れば REQUIRES_ACTION になる")
                .isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
    }

    @Test
    @DisplayName("AC-79: E2' — 照合は保存した pending_update_expires_at / target_snapshot と現在の items で行う")
    void AC79_保存値と現在itemsで照合する() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac79_paid", "invoice.paid", upgradeInvoice("in_planchange", "paid")));

        // items が旧 price のまま＝まだ切り替わっていない。保存した target と一致しないので APPLIED にしない。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac79_stale",
                "customer.subscription.pending_update_applied", planSubscription(OLD_PRICE_REF, null)));
        assertThat(changeStatus())
                .as("保存した target_snapshot と現在 items が一致しないのに APPLIED にしない")
                .isNotEqualTo(BillingContractChangeStatus.APPLIED);

        // 陽性対照: items が保存した target と一致すれば APPLIED。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac79_match",
                "customer.subscription.pending_update_applied", planSubscription(TARGET_PRICE_REF, null)));
        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.APPLIED);
    }

    // ═════════ AC-80 / AC-81: 確定の条件 ═════════

    @Test
    @DisplayName("AC-80: pending_update_applied は invoice.paid 済みを再確認してから APPLIED にする")
    void AC80_paid未確定ならAPPLIEDにしない() throws Exception {
        // invoice.paid をまだ受けていない状態で applied が来ても確定させない。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac80_applied",
                "customer.subscription.pending_update_applied", planSubscription(TARGET_PRICE_REF, null)));
        assertThat(changeStatus())
                .as("支払いの確定点は invoice.paid だけ（E6'）")
                .isNotEqualTo(BillingContractChangeStatus.APPLIED);

        // 陽性対照: paid が来てから applied を受け直せば APPLIED。
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac80_paid", "invoice.paid", upgradeInvoice("in_planchange", "paid")));
        postSigned(StripeWebhookPayloadFixture.event("evt_ac80_applied2",
                "customer.subscription.pending_update_applied", planSubscription(TARGET_PRICE_REF, null)));
        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.APPLIED);
    }

    @Test
    @DisplayName("AC-81: pending_update_expired は change を FAILED にし旧プランを維持する")
    void AC81_expiredはFAILEDで旧プラン維持() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event("evt_ac81", "customer.subscription.pending_update_expired",
                planSubscription(OLD_PRICE_REF, null)));

        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.FAILED);
        assertThat(billingContractRepository.findByIdAndDeletedAtIsNull(billingContractId)
                .orElseThrow().getPlanKey())
                .as("旧プランのまま（to_plan_key へ進めない）").isEqualTo("BASIC");
    }

    // ═════════ AC-82 / AC-83: 順序と単調性 ═════════

    @Test
    @DisplayName("AC-82: 古い event（created が小さい）で現状態を戻さない")
    void AC82_古いeventで戻さない() throws Exception {
        long newer = 1_800_000_200L;
        long older = 1_800_000_100L;

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac82_paid", "invoice.paid", upgradeInvoice("in_planchange", "paid"), newer));
        postSigned(StripeWebhookPayloadFixture.event("evt_ac82_applied",
                "customer.subscription.pending_update_applied",
                planSubscription(TARGET_PRICE_REF, null), newer));
        assertThat(changeStatus()).as("前提: 先に APPLIED まで進んでいる")
                .isEqualTo(BillingContractChangeStatus.APPLIED);

        // 遅れて届いた古い 3DS 要求で APPLIED を巻き戻さない。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac82_late",
                "invoice.payment_action_required", upgradeInvoice("in_planchange", "open"), older));

        assertThat(changeStatus())
                .as("判定は EventEnvelope.createdEpochSec（PR5 が使っている軸）に寄せる")
                .isEqualTo(BillingContractChangeStatus.APPLIED);
    }

    @Test
    @DisplayName("AC-83: REQUIRES_ACTION は pending_update が存続する間は戻さない（単調維持）")
    void AC83_REQUIRES_ACTIONは単調維持される() throws Exception {
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac83", "invoice.payment_action_required", upgradeInvoice("in_planchange", "open")));
        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);

        // pending_update がまだ存続している間の updated では PENDING_PAYMENT へ戻さない。
        postSigned(StripeWebhookPayloadFixture.event("evt_ac83_updated", "customer.subscription.updated",
                planSubscription(OLD_PRICE_REF, pendingUpdate(Instant.now().plusSeconds(1_800).getEpochSecond()))));

        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
    }

    // ═════════ AC-84〜86: PR5 / PR6a の性質を壊さない回帰 ═════════

    @Test
    @DisplayName("AC-84: billing 所有でない invoice の payment_action_required は event id を消費せず F08.9 へ fallthrough する")
    void AC84_非billing所有はevent_idを消費しない() throws Exception {
        String foreignLine = StripeWebhookPayloadFixture.lineObject(
                "il_ac84", "会費", 1L, 1_000L, 0L, 0L, false, null);
        postSigned(StripeWebhookPayloadFixture.event("evt_ac84", "invoice.payment_action_required",
                StripeWebhookPayloadFixture.invoiceObject("in_ac84", "cus_f089_membership",
                        FOREIGN_SUBSCRIPTION_REF, "open", "jpy", 1_000L, 0L, 0L, 1_000L, foreignLine)));

        assertThat(changeStatus())
                .as("他人の invoice で自分の change を動かさない")
                .isEqualTo(BillingContractChangeStatus.PENDING_PAYMENT);
        assertThat(webhookEvent("evt_ac84").map(e -> e.getProcessStatus()).orElse(null))
                .as("billing が所有を主張しないので PROCESSED では確定しない")
                .isNotEqualTo(WebhookProcessStatus.PROCESSED);
    }

    @Test
    @DisplayName("AC-85: 署名不正は 400、所有確定後の一時失敗は 5xx（200 で握り潰さない）")
    void AC85_署名不正は400で一時失敗は5xx() throws Exception {
        int badSignature = postWithSignature(
                StripeWebhookPayloadFixture.event(
                        "evt_ac85_sig", "invoice.payment_action_required", upgradeInvoice("in_ac85", "open")),
                "t=1,v1=deadbeef").getResponse().getStatus();
        assertThat(badSignature).as("署名不正は 400").isEqualTo(400);

        // subscription は billing のものだが customer が一致しない＝所有はしているが処理できない一時失敗。
        String line = StripeWebhookPayloadFixture.lineObject(
                "il_ac85", "FULL プラン差額", 10L, 2_000L, 0L, 200L, false, 1000);
        int transientFailure = postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac85_transient", "invoice.payment_action_required",
                StripeWebhookPayloadFixture.invoiceObject("in_ac85b", "cus_someone_else",
                        BILLING_SUBSCRIPTION_REF, "open", "jpy", 2_000L, 0L, 200L, 2_200L, line)))
                .getResponse().getStatus();
        assertThat(transientFailure).as("一時失敗は 5xx で再送させる").isGreaterThanOrEqualTo(500);
    }

    @Test
    @DisplayName("AC-86: 同一 event の再送は冪等（二度目で状態も投影も動かない）")
    void AC86_同一eventの再送は冪等() throws Exception {
        String payload = StripeWebhookPayloadFixture.event(
                "evt_ac86", "invoice.payment_action_required", upgradeInvoice("in_planchange", "open"));

        postSigned(payload);
        long versionAfterFirst = changeRepository.findByIdAndDeletedAtIsNull(changeId).orElseThrow().getVersion();
        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);

        postSigned(payload);

        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
        assertThat(changeRepository.findByIdAndDeletedAtIsNull(changeId).orElseThrow().getVersion())
                .as("再送で change 行を二度更新しない").isEqualTo(versionAfterFirst);
    }

    // ═════════ AC-87: 保留行は自動 drain しない ═════════

    @Test
    @DisplayName("AC-87: 既に RECEIVED で溜まっている保留行は、後続イベントの処理で自動 drain されない（運用の手動再投入で拾う）")
    void AC87_保留行を自動drainしない() throws Exception {
        // PR5 期に RECEIVED のまま溜まった行を模す（Stripe は 200 済みを再送しない）。
        // id は BINARY(16) NOT NULL で既定値を持たない（V72.008）。省くと INSERT 自体が落ちる。
        jdbcTemplate.update("""
                INSERT INTO stripe_webhook_events
                    (id, event_id, type, livemode, received_at, process_status, attempt_count)
                VALUES (UNHEX(REPLACE(UUID(), '-', '')), 'evt_ac87_backlog',
                        'invoice.payment_action_required', 0, NOW(6), 'RECEIVED', 0)
                """);

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac87_new", "invoice.payment_action_required", upgradeInvoice("in_planchange", "open")));

        assertThat(webhookEvent("evt_ac87_backlog").orElseThrow().getProcessStatus())
                .as("PR6b-1 では自動 drain を作らない（正本に明記して放置しない）")
                .isEqualTo(WebhookProcessStatus.RECEIVED);
        assertThat(changeStatus())
                .as("陽性対照: 新しく届いた同種イベントは処理される")
                .isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);
    }

    // ═════════ AC-72: リダイレクトを伴わない 3DS ═════════

    @Test
    @DisplayName("AC-72: リダイレクトを伴わない 3DS（in-page 解決）でも APPLIED へ収束する（戻りのエンドポイントを通らない）")
    void AC72_inPage3DSでもAPPLIEDへ収束する() throws Exception {
        // 3DS 要求 → 利用者はページ内で認証を済ませる（/billing/payment-action/return を一度も踏まない）。
        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac72_action", "invoice.payment_action_required", upgradeInvoice("in_planchange", "open")));
        assertThat(changeStatus()).isEqualTo(BillingContractChangeStatus.REQUIRES_ACTION);

        postSigned(StripeWebhookPayloadFixture.event(
                "evt_ac72_paid", "invoice.paid", upgradeInvoice("in_planchange", "paid")));
        postSigned(StripeWebhookPayloadFixture.event("evt_ac72_applied",
                "customer.subscription.pending_update_applied", planSubscription(TARGET_PRICE_REF, null)));

        assertThat(changeStatus())
                .as("収束の条件に「戻りの cookie を消費したこと」を混ぜてはならない"
                        + "（AC-60〜69 の cookie 機構はリダイレクト型のときだけの話である）")
                .isEqualTo(BillingContractChangeStatus.APPLIED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_return_state_nonces", Long.class))
                .as("in-page 経路では return state を一度も発行しない").isZero();
    }
}
