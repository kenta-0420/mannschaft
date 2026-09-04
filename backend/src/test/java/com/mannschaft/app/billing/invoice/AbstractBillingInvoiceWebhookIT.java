package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.ActiveContractPointerRepository;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.billing.api.BillingCustomerJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentEntity;
import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceLineEntity;
import com.mannschaft.app.billing.api.BillingInvoiceLineJpaRepository;
import com.mannschaft.app.payment.StripeWebhookEventEntity;
import com.mannschaft.app.payment.StripeWebhookEventRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F20.1 PR5 試練の共通土台。
 *
 * <p><b>座席の選び方</b>: 投影の実装がどのクラス・どの Repository を経由するかは未定のため、
 * 「実装が何をしようと必ず通る収束点」だけを掴む。すなわち入口は <b>実 {@code StripeWebhookController}
 * ＋実署名検証（フィルタ有効）</b>、出口は <b>実 DB の投影 3 表と {@code stripe_webhook_events}</b> である。
 * サービスをモックしないため、実装が別経路を作っても検証が空振りしない。</p>
 *
 * <p><b>{@code @Transactional} を付けない</b>: 付けるとテストの tx にぶら下がって commit が起きず、
 * webhook 側のトランザクション境界（AC-20/AC-21/AC-26）が発火しないまま緑になる。
 * その代わり各テストの前に投影表を明示的に掃除する。</p>
 *
 * <p>{@link AbstractMySqlIntegrationTest} の構成は再宣言しない（TestContext Cache 分裂の回避）。
 * {@code @AutoConfigureMockMvc} のみ本基底で足し、派生は何も足さない。</p>
 */
@AutoConfigureMockMvc
abstract class AbstractBillingInvoiceWebhookIT extends AbstractMySqlIntegrationTest {

    /** billing 所有の scope（USER）。他テストと衝突しない専用 ID 帯を使う。 */
    protected static final long BILLING_SCOPE_ID = 920_501L;
    /** billing 所有でない（F08.9 会費側の）subscription。 */
    protected static final String FOREIGN_SUBSCRIPTION_REF = "sub_f089_membership_not_billing";

    protected static final String BILLING_CUSTOMER_REF = "cus_billing_fixture";
    protected static final String BILLING_SUBSCRIPTION_REF = "sub_billing_fixture";

    @Value("${mannschaft.stripe.webhook-secret}")
    protected String webhookSecret;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected BillingCustomerJpaRepository billingCustomerRepository;

    @Autowired
    protected BillingContractRepository billingContractRepository;

    @Autowired
    protected BillingInvoiceJpaRepository invoiceRepository;

    @Autowired
    protected BillingInvoiceLineJpaRepository invoiceLineRepository;

    @Autowired
    protected BillingInvoiceAdjustmentJpaRepository invoiceAdjustmentRepository;

    @Autowired
    protected StripeWebhookEventRepository webhookEventRepository;

    @Autowired
    protected ActiveContractPointerRepository activeContractPointerRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected UUID billingCustomerId;
    protected UUID billingContractId;

    @BeforeEach
    void seedBillingOwnedScope() {
        // 逆 FK 順で掃除する（設計書 05 §7 の cleanup 正本順）。
        jdbcTemplate.update("DELETE FROM billing_invoice_lines");
        jdbcTemplate.update("DELETE FROM billing_invoice_adjustments");
        jdbcTemplate.update("DELETE FROM billing_invoices");
        jdbcTemplate.update("DELETE FROM stripe_webhook_events");
        jdbcTemplate.update("DELETE FROM active_contract_pointers WHERE scope_id = ?", BILLING_SCOPE_ID);
        jdbcTemplate.update("DELETE FROM billing_contracts WHERE scope_id = ?", BILLING_SCOPE_ID);
        jdbcTemplate.update("DELETE FROM billing_customers WHERE scope_id = ?", BILLING_SCOPE_ID);

        Instant now = Instant.now();
        BillingCustomerEntity customer = billingCustomerRepository.saveAndFlush(
                BillingCustomerEntity.builder()
                        .scopeKind(EntitlementScopeKind.USER)
                        .scopeId(BILLING_SCOPE_ID)
                        .pspCustomerRef(BILLING_CUSTOMER_REF)
                        .billingEmail("billing-taro@example.com")
                        .billingName("請求先 太郎")
                        .status("ACTIVE")
                        .provisionAttempts(0)
                        .version(0L)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
        billingCustomerId = customer.getId();

        BillingContractEntity contract = billingContractRepository.saveAndFlush(
                BillingContractEntity.builder()
                        .scopeKind(EntitlementScopeKind.USER)
                        .scopeId(BILLING_SCOPE_ID)
                        .contractKind(ContractKind.PLAN)
                        .planKey("BASIC")
                        .status(ContractStatus.ACTIVE)
                        .billingCustomerId(customer.getId())
                        .pspCustomerRef(BILLING_CUSTOMER_REF)
                        .pspSubscriptionRef(BILLING_SUBSCRIPTION_REF)
                        .version(0L)
                        .contractedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                        // 投影イベントの period_end（2026-02-01）とは<b>別の値</b>にする。
                        // 同じ値にすると「延長された」検証が、何もしなくても通る空虚な緑になる。
                        .currentPeriodEnd(LocalDateTime.of(2026, 1, 15, 0, 0))
                        .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                        .updatedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                        .build());
        billingContractId = contract.getId();
    }

    // ───────────── webhook 送信ヘルパ ─────────────

    /** 正しい署名で platform Webhook を叩き、生の {@link MvcResult} を返す（ステータスを検査したい側で使う）。 */
    protected MvcResult postSigned(String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .header("Stripe-Signature", StripeWebhookPayloadFixture.signature(payload, webhookSecret))
                        .contentType("application/json")
                        .content(payload))
                .andReturn();
    }

    /** 任意の署名ヘッダで叩く（AC-11 の署名不正検体で使う）。 */
    protected MvcResult postWithSignature(String payload, String signatureHeader) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/stripe")
                        .header("Stripe-Signature", signatureHeader)
                        .contentType("application/json")
                        .content(payload))
                .andReturn();
    }

    // ───────────── 観測ヘルパ ─────────────

    protected Optional<BillingInvoiceEntity> invoiceOf(String pspInvoiceRef) {
        return invoiceRepository.findByPspInvoiceRef(pspInvoiceRef);
    }

    protected BillingInvoiceEntity requireInvoice(String pspInvoiceRef) {
        return invoiceOf(pspInvoiceRef).orElseThrow(() ->
                new AssertionError("billing_invoices に投影行が無い: psp_invoice_ref=" + pspInvoiceRef));
    }

    protected List<BillingInvoiceLineEntity> linesOf(String pspInvoiceRef) {
        return invoiceLineRepository.findByInvoiceId(requireInvoice(pspInvoiceRef).getId());
    }

    protected List<BillingInvoiceAdjustmentEntity> adjustmentsOf(String pspInvoiceRef) {
        return invoiceAdjustmentRepository.findByInvoiceId(requireInvoice(pspInvoiceRef).getId());
    }

    protected Optional<StripeWebhookEventEntity> webhookEvent(String eventId) {
        return webhookEventRepository.findByEventId(eventId);
    }

    /**
     * 保留イベント（PR5 で扱わない種別）の滞留件数を数える運用クエリ（AC-24）。
     *
     * <p>新規列を足さずに「RECEIVED のまま滞留しているのはどの種別か」を数えられることを、
     * この SQL 自体が示す。実装側はこれと同じ形の運用クエリを持てばよい。</p>
     */
    protected long countPendingReceivedEvents(String... types) {
        String placeholders = String.join(",", java.util.Collections.nCopies(types.length, "?"));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stripe_webhook_events "
                        + "WHERE process_status = 'RECEIVED' AND type IN (" + placeholders + ")",
                Long.class, (Object[]) types);
        return count == null ? 0L : count;
    }
}
