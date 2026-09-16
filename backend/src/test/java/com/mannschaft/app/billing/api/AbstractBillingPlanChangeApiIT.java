package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.billing.BillingChangePreviewEntity;
import com.mannschaft.app.billing.BillingChangePreviewRepository;
import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingContractOperationRepository;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingPaymentGateway;
import com.mannschaft.app.billing.BillingPlanChangeGateway;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceCreationSource;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.EntitlementSourceKind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Billing Center PR6b-1 — A群（事前見積り）/ B群（upgrade の実行）の受け入れテスト共通土台（試練・red）。
 *
 * <h2>第6隊・第7隊への発注書（このクラス群が要求する契約）</h2>
 * <p>正本 05_billing_center.md:380-381 に従い、<b>エンドポイントは2本</b>。</p>
 * <pre>
 * POST /api/v1/me/billing/contracts/{contractId}/change-previews
 *      header: Idempotency-Key
 *      body  : {"toProductKind":"PLAN","toProductKey":"FULL","version":N}
 *      201   : $.data = {previewId:UUID, kind:"UPGRADE", amountDueNow:Money, effectiveAt, expiresAt}
 *
 * POST /api/v1/me/billing/contracts/{contractId}/changes
 *      header: Idempotency-Key
 *      body  : {"previewId":"...","version":N}
 *      202   : $.data = {changeId:UUID, status:enum, effectiveAt}   ← clientSecret は返さない（AC-25）
 * </pre>
 * <p>{@code Money = {currency, amountIncludingTax, amountExcludingTax, taxAmount, taxName, taxRateBasisPoints}}
 * （正本 05:393）。エラーは既存の error 包み（{@code $.error.code} / {@code $.error.details.reason} /
 * {@code $.error.details.availableAt}）。</p>
 *
 * <p><b>なぜ HTTP 経由で書くか</b>: 実装（Service/Controller/DTO）は未着手であり、Java 型を直接参照すると
 * テストソースがコンパイルできず「赤の理由」が観測できない。URL 文字列と JSON 構造だけを観測すれば
 * コンパイルは通り、<b>実行されて 404／形が違う</b>という狙った赤になる（PR6a
 * {@code AbstractBillingCancelResumeApiIT} と同じ流儀）。</p>
 *
 * <p><b>{@code @Transactional} を付けない</b>: 付けるとテストの tx にぶら下がって commit が起きず、
 * Saga の tx 境界・pointer の解放が発火しないまま緑になる（第一次キャッシュの罠も同じ）。
 * フィクスチャ投入も検証も {@link TransactionTemplate} で区切り、読み出し前に
 * {@link EntityManager#clear()} する。</p>
 */
@AutoConfigureMockMvc
abstract class AbstractBillingPlanChangeApiIT extends AbstractMySqlIntegrationTest {

    /** 事前見積り（A群）。 */
    protected static final String PREVIEW_PATH = "/api/v1/me/billing/contracts/%s/change-previews";
    /** 変更の実行（B群）。 */
    protected static final String CHANGES_PATH = "/api/v1/me/billing/contracts/%s/changes";

    /** 変更前プラン（seed 済み: plans に FREE/BASIC/FULL がある）。 */
    protected static final String FROM_PLAN_KEY = "BASIC";
    /** 変更後プラン（上位）。 */
    protected static final String TO_PLAN_KEY = "FULL";
    protected static final String FEATURE_KEY = "ads.hide";

    /** 変更前 band の税込額。 */
    protected static final long FROM_AMOUNT = 1_200L;
    /** 変更後 band の税込額（必ず上位＝高い）。 */
    protected static final long TO_AMOUNT = 3_300L;

    /** AC-2 の検体。日割り計算では絶対に出てこない値にして「自前計算していたら落ちる」形にする。 */
    protected static final long STRIPE_QUOTED_AMOUNT = 777L;

    protected static final String TO_STRIPE_PRICE_REF = "price_pr6b1_full";
    protected static final String FROM_STRIPE_PRICE_REF = "price_pr6b1_basic";

    /** Stripe は実際には呼ばない。呼ばれたこと・呼ばれなかったことが観測対象。 */
    @MockitoBean protected BillingPaymentGateway billingPaymentGateway;
    /** プラン変更の Stripe 窓口（試練A が置いた発注書。AC-2/29/30/31/46 の観測点）。 */
    @MockitoBean protected BillingPlanChangeGateway planChangeGateway;

    @Autowired protected MockMvc mockMvc;
    @Autowired protected BillingContractRepository billingContractRepository;
    @Autowired protected BillingChangePreviewRepository changePreviewRepository;
    @Autowired protected BillingContractOperationRepository operationRepository;
    @Autowired protected EntitlementRepository entitlementRepository;
    @Autowired protected TransactionTemplate transactionTemplate;
    @Autowired protected Clock clock;
    @PersistenceContext protected EntityManager entityManager;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    protected Long userId;
    protected UUID customerId;
    protected UUID contractId;
    protected UUID fromBandId;
    protected UUID toBandId;
    protected String subscriptionRef;

    /** {@link #insertForeignScope} で作った別スコープの後始末対象。 */
    protected final List<Long> foreignScopeUserIds = new java.util.ArrayList<>();

    // ============================================================
    // 既定のフィクスチャ（BASIC 契約 → FULL へ upgrade できる状態）
    // ============================================================

    /** 既定の検体一式を作る（派生の {@code @BeforeEach} から呼ぶ）。 */
    protected void seedUpgradableContract(String suffix) {
        userId = insertUser(suffix);
        subscriptionRef = "sub_pr6b1_" + userId;
        customerId = insertCustomer(userId);
        fromBandId = insertBand(FROM_PLAN_KEY, FROM_AMOUNT, BillingPriceVersionStatus.ACTIVE,
                FROM_STRIPE_PRICE_REF, 1, null);
        toBandId = insertBand(TO_PLAN_KEY, TO_AMOUNT, BillingPriceVersionStatus.ACTIVE,
                TO_STRIPE_PRICE_REF, 1, null);
        contractId = insertContract(fromBandId, LocalDateTime.now(clock).plusDays(20).withNano(0));
        stubStripeQuote(STRIPE_QUOTED_AMOUNT);
    }

    // ============================================================
    // Stripe スタブ
    // ============================================================

    /** AC-2: Stripe の見積り API が返す額を仕込む（こちらで日割りしないことの観測点）。 */
    protected void stubStripeQuote(long amountDueNow) {
        org.mockito.BDDMockito.given(planChangeGateway.previewPlanChange(
                        org.mockito.ArgumentMatchers.any()))
                .willReturn(new BillingPlanChangeGateway.PlanChangeQuote(
                        "JPY", amountDueNow, amountDueNow - 70L, 70L, "消費税", 1_000,
                        Instant.now().truncatedTo(ChronoUnit.SECONDS),
                        Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS),
                        Instant.now().truncatedTo(ChronoUnit.SECONDS)));
    }

    /**
     * AC-32/33/34/35 の4検体を仕込む。
     *
     * @param invoiceRef           差額請求（0円で invoice が立たない検体は null）
     * @param invoiceStatus        {@code paid} なら既に支払い済み
     * @param pendingUpdatePresent true なら 3DS 要求（E6' により<b>成功時は false</b>）
     */
    protected void stubStripeApply(String invoiceRef, String invoiceStatus, boolean pendingUpdatePresent) {
        org.mockito.BDDMockito.given(planChangeGateway.applyPlanChange(
                        org.mockito.ArgumentMatchers.any()))
                .willReturn(new BillingPlanChangeGateway.PlanChangeApplyResult(
                        invoiceRef, invoiceStatus, pendingUpdatePresent,
                        pendingUpdatePresent ? Instant.now().plus(23, ChronoUnit.HOURS) : null,
                        pendingUpdatePresent ? "{\"priceRef\":\"" + TO_STRIPE_PRICE_REF + "\"}" : null,
                        Instant.now().truncatedTo(ChronoUnit.SECONDS)));
    }

    /** gateway のメソッド名だけで呼び出し回数を数える（arity 非依存・PR6a と同流儀）。 */
    protected long planChangeCalls(String methodName) {
        return org.mockito.Mockito.mockingDetails(planChangeGateway).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(methodName))
                .count();
    }

    /** 直近の {@code applyPlanChange} に渡された引数（AC-29/30/31 の観測点）。 */
    protected BillingPlanChangeGateway.PlanChangeApplyCommand lastApplyCommand() {
        return org.mockito.Mockito.mockingDetails(planChangeGateway).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("applyPlanChange"))
                .reduce((a, b) -> b)
                .map(i -> (BillingPlanChangeGateway.PlanChangeApplyCommand) i.getArguments()[0])
                .orElseThrow(() -> new AssertionError("applyPlanChange が一度も呼ばれていない"));
    }

    // ============================================================
    // リクエスト
    // ============================================================

    /** 事前見積り。 */
    protected ResultActions preview(long actorId, UUID targetContractId, String toProductKey,
                                    long version, String idempotencyKey) throws Exception {
        return previewRaw(actorId, targetContractId, idempotencyKey,
                "{\"toProductKind\":\"PLAN\",\"toProductKey\":\"" + toProductKey
                        + "\",\"version\":" + version + "}");
    }

    /** 任意の body で事前見積り（AC-17 の「送ってはならない項目」検体で使う）。 */
    protected ResultActions previewRaw(long actorId, UUID targetContractId, String idempotencyKey,
                                       String body) throws Exception {
        return mockMvc.perform(post(String.format(PREVIEW_PATH, targetContractId))
                .with(user(String.valueOf(actorId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 変更の実行。 */
    protected ResultActions change(long actorId, UUID targetContractId, UUID previewId,
                                   long version, String idempotencyKey) throws Exception {
        return mockMvc.perform(post(String.format(CHANGES_PATH, targetContractId))
                .with(user(String.valueOf(actorId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewId\":\"" + previewId + "\",\"version\":" + version + "}"));
    }

    /** 見積りを作って previewId を取り出す（B群の前段）。 */
    protected UUID createPreviewId() throws Exception {
        MvcResult result = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andReturn();
        JsonNode data = body(result).path("data");
        String id = data.path("previewId").asText(null);
        if (id == null || id.isBlank()) {
            throw new AssertionError("previewId が返っていない（事前見積りが未実装）: status="
                    + result.getResponse().getStatus()
                    + ", body=" + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        return UUID.fromString(id);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected String newKey() {
        return UUID.randomUUID().toString();
    }

    // ============================================================
    // フィクスチャ
    // ============================================================

    /** 認可（{@code BillingOperationAuthorizer}）が users 行を実読するため実ユーザーを作る。 */
    protected Long insertUser(String suffix) {
        return transactionTemplate.execute(tx -> {
            UserEntity u = UserEntity.builder()
                    .email("pr6b1-" + suffix + "-" + System.nanoTime() + "@example.com")
                    .lastName("試練").firstName(suffix).displayName("試練 " + suffix)
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build();
            entityManager.persist(u);
            entityManager.flush();
            return u.getId();
        });
    }

    protected UUID insertCustomer(Long ownerId) {
        return transactionTemplate.execute(tx -> {
            Instant now = Instant.now();
            BillingCustomerEntity c = BillingCustomerEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER)
                    .scopeId(ownerId)
                    .pspCustomerRef("cus_pr6b1_" + ownerId)
                    .status("ACTIVE")
                    .provisionAttempts(0)
                    .version(0L)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            entityManager.persist(c);
            entityManager.flush();
            return c.getId();
        });
    }

    /**
     * 価格 band を1本作る（band は price_version にぶら下がるため親も作る）。
     *
     * @param stripePriceRef null なら AC-20 の「Stripe Price ref が null」検体
     * @param maxMembers     null なら上限なし
     */
    protected UUID insertBand(String productKey, long amountIncludingTax,
                              BillingPriceVersionStatus status, String stripePriceRef,
                              int minMembers, Integer maxMembers) {
        return transactionTemplate.execute(tx -> {
            Instant now = Instant.now();
            BillingPriceVersionEntity version = BillingPriceVersionEntity.builder()
                    .productKind(BillingProductKind.PLAN)
                    .productKey(productKey)
                    .scopeKind(EntitlementScopeKind.USER)
                    .catalogRevision("pr6b1-" + productKey + "-" + System.nanoTime())
                    .revisionNo(System.nanoTime())
                    .status(status)
                    .provisionAttempts(0)
                    .effectiveFrom(now.minus(30, ChronoUnit.DAYS))
                    .effectiveUntil(null)
                    .lockVersion(0L)
                    .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                    .createdAt(now)
                    .build();
            entityManager.persist(version);
            entityManager.flush();

            long excluding = Math.round(amountIncludingTax / 1.1d);
            BillingPriceBandVersionEntity band = BillingPriceBandVersionEntity.builder()
                    .productKind(BillingProductKind.PLAN)
                    .productKey(productKey)
                    .scopeKind(EntitlementScopeKind.USER)
                    .bandNo(1)
                    .minMembers(minMembers)
                    .maxMembers(maxMembers)
                    .priceVersionId(version.getId())
                    .stripePriceRef(stripePriceRef)
                    .currency("JPY")
                    .inputAmount(amountIncludingTax)
                    .taxBehavior(BillingTaxBehavior.INCLUSIVE)
                    .taxCodeSnapshot("txcd_10000000")
                    .taxMasterSnapshot("{\"name\":\"消費税\",\"rateBasisPoints\":1000}")
                    .amountExcludingTax(excluding)
                    .taxAmount(amountIncludingTax - excluding)
                    .taxRateBasisPoints(1_000)
                    .taxNameSnapshot("消費税")
                    .includedInPrice(true)
                    .amountIncludingTax(amountIncludingTax)
                    .effectiveFrom(now.minus(30, ChronoUnit.DAYS))
                    .effectiveUntil(null)
                    .status(status)
                    .lockVersion(0L)
                    .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                    .createdAt(now)
                    .build();
            entityManager.persist(band);
            entityManager.flush();
            return band.getId();
        });
    }

    /**
     * 有償 PLAN 契約（USER スコープ）を作る。
     *
     * @param bandId    null なら AC-19 の「{@code price_band_version_id} が NULL の既存契約」検体
     * @param periodEnd {@code current_period_end}（AC-18 の月末境界検体で効く）
     */
    protected UUID insertContract(UUID bandId, LocalDateTime periodEnd) {
        return transactionTemplate.execute(tx -> {
            BillingContractEntity c = BillingContractEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                    .contractKind(ContractKind.PLAN).planKey(FROM_PLAN_KEY)
                    .status(ContractStatus.ACTIVE)
                    .priceJpySnapshot((int) FROM_AMOUNT)
                    .memberCountSnapshot(1)
                    .priceBandVersionId(bandId)
                    .billingCustomerId(customerId)
                    .pspCustomerRef("cus_pr6b1_" + userId)
                    .pspSubscriptionRef(subscriptionRef)
                    .currentPeriodEnd(periodEnd)
                    .contractedAt(LocalDateTime.now(clock).minusMonths(1))
                    .createdBy(userId).payerUserId(userId)
                    .version(0L)
                    .build();
            entityManager.persist(c);
            entityManager.flush();
            return c.getId();
        });
    }

    /** 契約由来の権利行（AC-36 の「paid の前は新権利を発行しない」観測の出発点）。 */
    protected void insertEntitlement(UUID sourceContractId, String featureKey) {
        transactionTemplate.executeWithoutResult(tx -> {
            EntitlementEntity e = EntitlementEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                    .featureKey(featureKey)
                    .sourceKind(EntitlementSourceKind.PLAN).sourceRefId(sourceContractId)
                    .validFrom(LocalDateTime.now(clock).minusMonths(1))
                    .validUntil(null)
                    .build();
            entityManager.persist(e);
            entityManager.flush();
        });
    }

    /**
     * band を後から書き換える（「preview を作った後に世界が変わった」検体）。
     *
     * <p>JPA 経由で書き換えるのは、native UPDATE で BINARY(16) の主キーを束縛するより安全だから。</p>
     *
     * @param bandId   対象 band
     * @param mutation 書き換え内容
     */
    protected void mutateBand(UUID bandId, java.util.function.Consumer<BillingPriceBandVersionEntity> mutation) {
        transactionTemplate.executeWithoutResult(tx -> {
            BillingPriceBandVersionEntity band =
                    entityManager.find(BillingPriceBandVersionEntity.class, bandId);
            if (band == null) {
                throw new AssertionError("band が無い: " + bandId);
            }
            mutation.accept(band);
            entityManager.flush();
        });
    }

    /**
     * 別スコープ（別ユーザー）の契約一式を作る（AC-10 の IDOR 検体）。
     *
     * @param suffix 識別子
     * @return 別スコープの識別子
     */
    protected ForeignScope insertForeignScope(String suffix) {
        Long savedUser = userId;
        UUID savedCustomer = customerId;
        UUID savedContract = contractId;
        String savedSub = subscriptionRef;
        try {
            userId = insertUser(suffix);
            subscriptionRef = "sub_pr6b1_foreign_" + userId;
            customerId = insertCustomer(userId);
            contractId = insertContract(fromBandId, LocalDateTime.now(clock).plusDays(20).withNano(0));
            return new ForeignScope(userId, contractId);
        } finally {
            Long foreignUser = userId;
            userId = savedUser;
            customerId = savedCustomer;
            contractId = savedContract;
            subscriptionRef = savedSub;
            foreignScopeUserIds.add(foreignUser);
        }
    }

    /**
     * 別スコープの識別子。
     *
     * @param userId     所有ユーザー
     * @param contractId 契約
     */
    protected record ForeignScope(Long userId, UUID contractId) {
    }

    // ============================================================
    // DB 実読
    // ============================================================

    protected long contractVersion() {
        return reloadContract().getVersion();
    }

    protected BillingContractEntity reloadContract() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        });
    }

    protected BillingChangePreviewEntity reloadPreview(UUID previewId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return changePreviewRepository.findByIdAndDeletedAtIsNull(previewId)
                    .orElseThrow(() -> new AssertionError(
                            "billing_change_previews に行が無い: previewId=" + previewId));
        });
    }

    /** 契約に紐づく変更行を全部読む（status 絞り込みなし）。 */
    protected List<BillingContractChangeEntity> changesOf() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return entityManager.createQuery(
                            "SELECT c FROM BillingContractChangeEntity c "
                                    + "WHERE c.contractId = :cid AND c.deletedAt IS NULL "
                                    + "ORDER BY c.createdAt ASC", BillingContractChangeEntity.class)
                    .setParameter("cid", contractId)
                    .getResultList();
        });
    }

    /** 変更行が1件だけあることを要求して返す。 */
    protected BillingContractChangeEntity requireSingleChange() {
        List<BillingContractChangeEntity> rows = changesOf();
        if (rows.size() != 1) {
            throw new AssertionError("billing_contract_changes が1件でない: " + rows.size() + "件");
        }
        return rows.get(0);
    }

    protected BillingContractOperationEntity operationOf(UUID operationId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return operationRepository.findByIdAndDeletedAtIsNull(operationId)
                    .orElseThrow(() -> new AssertionError("operation が無い: " + operationId));
        });
    }

    protected long operationCount() {
        return countBy("SELECT COUNT(*) FROM billing_contract_operations WHERE contract_id = :c");
    }

    protected long pointerCount() {
        return countBy("SELECT COUNT(*) FROM active_billing_contract_operation_pointers "
                + "WHERE contract_id = :c");
    }

    protected long countBy(String sql) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(sql)
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }

    protected long activeEntitlementCount() {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return (long) entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.PLAN, contractId).size();
        });
    }

    /** native UPDATE（JPA の {@code @PreUpdate} を通さずに「世界が変わった」状態を作る）。 */
    protected void executeNative(String sql, java.util.Map<String, Object> params) {
        transactionTemplate.executeWithoutResult(tx -> {
            var query = entityManager.createNativeQuery(sql);
            params.forEach(query::setParameter);
            query.executeUpdate();
            entityManager.flush();
        });
    }

    /** テストごとの後始末（scope_id で限定して他クラスの検体を壊さない）。 */
    protected void cleanupScope() {
        cleanupScopeOf(userId);
        foreignScopeUserIds.forEach(this::cleanupScopeOf);
        foreignScopeUserIds.clear();
    }

    /**
     * 指定スコープの後始末。
     *
     * @param scopeOwnerId 対象スコープの所有ユーザー
     */
    protected void cleanupScopeOf(Long scopeOwnerId) {
        final Long targetId = scopeOwnerId;
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("DELETE FROM entitlements WHERE scope_id = :id")
                    .setParameter("id", targetId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_change_previews WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id)")
                    .setParameter("id", targetId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_changes WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id)")
                    .setParameter("id", targetId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM active_billing_contract_operation_pointers WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id)")
                    .setParameter("id", targetId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_operations WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id)")
                    .setParameter("id", targetId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :id")
                    .setParameter("id", targetId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_customers WHERE scope_id = :id")
                    .setParameter("id", targetId).executeUpdate();
        });
    }
}
