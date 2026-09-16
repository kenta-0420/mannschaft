package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingPaymentGateway;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Billing Center PR6a — 解約 / 解約撤回 API（B群 AC-22〜39・C群 AC-40〜49）の受け入れテスト共通基盤（試練・red）。
 *
 * <h2>第6隊への発注書（このクラス群が要求する契約）</h2>
 * <p>正本 05_billing_center.md:334-335 と殿の設計判断 D6 に従い、<b>エンドポイントは2本だけ</b>。</p>
 * <pre>
 * POST   /api/v1/me/billing/contracts/{contractId}/cancel   （解約予約）
 * DELETE /api/v1/me/billing/contracts/{contractId}/cancel   （解約撤回）
 * </pre>
 * <ul>
 *   <li>両方とも {@code Idempotency-Key} ヘッダ必須（欠落は Spring が 400）。</li>
 *   <li>両方とも リクエストボディ {@code {"version": N}} 必須（{@code billing_contracts.version} の
 *       CAS 期待値。不一致は 409・AC-27/AC-44）。DELETE でもボディを受ける
 *       （POST と同一の request hash 計算を使うため。{@code BillingCheckoutController} の
 *       {@code requestHash(actorId, path, request)} と同流儀）。</li>
 *   <li>成功時 200。本文は {@code ApiResponse} 包みで、{@code $.data} は次の形:
 *     <pre>
 *     contractId      : String  契約 UUID
 *     contractStatus  : String  契約そのものの状態（解約予約後も ACTIVE のまま・AC-23）
 *     status          : String  解約予約の状態（SCHEDULED＝予約中 / ACTIVE＝予約なし・AC-22/AC-40）
 *     scheduledAt     : String  解約予約を入れた時刻（撤回後は null）
 *     endAt           : String  非 null 必須。利用可能期限＝current_period_end（AC-22/AC-37c）
 *     currentPeriodEnd: String  endAt と同値（表示用・AC-60 との整合）
 *     version         : Number  更新後の CAS version
 *     canCancel       : Boolean 解約できるか
 *     canResume       : Boolean 撤回できるか（期末を跨いだら false・AC-46）
 *     </pre>
 *   </li>
 *   <li>エラーは既存の error 包み（{@code $.error.code}）。pointer 競合・引継競合は
 *       {@code ENTITLEMENT_021}（AC-38）、再解約・撤回不能・期末不正は {@code ENTITLEMENT_011}、
 *       Stripe 呼び出し失敗は HTTP 502（AC-35）。</li>
 * </ul>
 *
 * <p><b>なぜ HTTP 経由で書くか</b>: 実装（Service/Controller/DTO）は未着手であり、Java 型を直接参照すると
 * テストソースがコンパイルできず「赤の理由」が観測できない。URL 文字列と JSON 構造だけを観測すれば
 * コンパイルは通り、<b>実行されて 404/形が違う</b>という狙った赤になる（PR5 の試練
 * {@code BillingInvoiceApiContractRedIT} と同じ流儀）。</p>
 *
 * <p><b>第一次キャッシュの罠回避</b>: クラスに {@code @Transactional} を付けず、フィクスチャ投入も検証も
 * {@link TransactionTemplate} で tx を区切り、読み出し前に {@link EntityManager#clear()} する。</p>
 */
@AutoConfigureMockMvc
abstract class AbstractBillingCancelResumeApiIT extends AbstractMySqlIntegrationTest {

    /** 解約 / 撤回の唯一のパス（D6・正本 05:334-335）。 */
    protected static final String CANCEL_PATH = "/api/v1/me/billing/contracts/%s/cancel";

    protected static final String PLAN_KEY = "FULL";
    protected static final String FEATURE_KEY = "ads.hide";
    protected static final int PRICE_JPY = 1_200;

    /** Stripe は実際には呼ばない。呼ばれたこと自体が観測対象（AC-22/AC-35/AC-37c/AC-40/AC-45）。 */
    @MockitoBean
    protected BillingPaymentGateway billingPaymentGateway;

    @Autowired protected MockMvc mockMvc;
    @Autowired protected BillingContractRepository billingContractRepository;
    @Autowired protected EntitlementRepository entitlementRepository;
    @Autowired protected TransactionTemplate transactionTemplate;
    @Autowired protected Clock clock;
    @PersistenceContext protected EntityManager entityManager;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    // ============================================================
    // リクエスト
    // ============================================================

    /** 解約（POST …/cancel）。 */
    protected ResultActions cancel(long actorId, UUID contractId, long version, String idempotencyKey)
            throws Exception {
        return mockMvc.perform(post(String.format(CANCEL_PATH, contractId))
                .with(user(String.valueOf(actorId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"));
    }

    /** 解約撤回（DELETE …/cancel）。 */
    protected ResultActions resume(long actorId, UUID contractId, long version, String idempotencyKey)
            throws Exception {
        return mockMvc.perform(delete(String.format(CANCEL_PATH, contractId))
                .with(user(String.valueOf(actorId)))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"));
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected String newKey() {
        return UUID.randomUUID().toString();
    }

    // ============================================================
    // Stripe 呼び出しの観測（arity 非依存）
    // ============================================================

    /**
     * gateway のメソッド名だけで呼び出し回数を数える。
     *
     * <p><b>なぜ {@code verify()} を使わないか</b>: AC-39/AC-47 は既存ポートへ
     * {@code cancelAtPeriodEnd(ref, operationId)} / {@code revertCancelAtPeriodEnd(ref, operationId)} を
     * <b>追加</b>することを要求している。実装前の今は2引数版が存在せずコンパイルできず、
     * 実装後に1引数版で {@code verify} すると今度はそちらが呼ばれず落ちる。
     * どちらの時点でも同じ意味を測れるよう、メソッド名で観測する。</p>
     */
    protected long stripeCalls(String methodName) {
        return org.mockito.Mockito.mockingDetails(billingPaymentGateway).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(methodName))
                .count();
    }

    /** 指定メソッドが、第1引数＝subscriptionRef で呼ばれた回数。 */
    protected long stripeCallsFor(String methodName, String subscriptionRef) {
        return org.mockito.Mockito.mockingDetails(billingPaymentGateway).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(methodName))
                .filter(i -> i.getArguments().length > 0 && subscriptionRef.equals(i.getArguments()[0]))
                .count();
    }

    /**
     * 期末の権威（AC-34）として Stripe が返す subscription スナップショットを仕込む。
     *
     * @param currentPeriodEnd null なら Stripe 側が期末を持たない検体（AC-37c）
     */
    protected void stubStripeSubscription(String subscriptionRef, boolean cancelAtPeriodEnd,
                                          LocalDateTime currentPeriodEnd) {
        org.mockito.BDDMockito.given(billingPaymentGateway.retrieveSubscription(subscriptionRef))
                .willReturn(new BillingPaymentGateway.SubscriptionSnapshot(
                        subscriptionRef, "active", cancelAtPeriodEnd, null,
                        currentPeriodEnd == null
                                ? null : currentPeriodEnd.atZone(clock.getZone()).toInstant(),
                        null));
    }

    // ============================================================
    // フィクスチャ
    // ============================================================

    /** 認可（{@code BillingOperationAuthorizer#requireCanManage}）が users 行をロックするため実ユーザーを作る。 */
    protected Long insertUser(String suffix) {
        return transactionTemplate.execute(tx -> {
            UserEntity u = UserEntity.builder()
                    .email("pr6a-" + suffix + "-" + System.nanoTime() + "@example.com")
                    .lastName("試練").firstName(suffix).displayName("試練 " + suffix)
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build();
            entityManager.persist(u);
            entityManager.flush();
            return u.getId();
        });
    }

    /**
     * 有償 PLAN 契約（USER スコープ）を作る。
     *
     * @param priceJpy    null なら無償契約（AC-25 の即時失効検体）
     * @param subRef      null なら PSP 未紐付（無償扱い）
     * @param periodEnd   current_period_end（AC-34/37/37b/37c の境界検体）
     * @param cancelledAt 非 null なら「既に解約予約済み」（AC-26/AC-40 の検体）
     */
    protected UUID insertContract(Long userId, ContractStatus status, Integer priceJpy, String subRef,
                                  LocalDateTime periodEnd, LocalDateTime cancelledAt) {
        return transactionTemplate.execute(tx -> {
            BillingContractEntity c = BillingContractEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                    .contractKind(ContractKind.PLAN).planKey(PLAN_KEY)
                    .status(status)
                    .priceJpySnapshot(priceJpy)
                    .pspSubscriptionRef(subRef)
                    .pspCustomerRef(subRef == null ? null : "cus_pr6a_" + userId)
                    .currentPeriodEnd(periodEnd)
                    .cancelledAt(cancelledAt)
                    .contractedAt(LocalDateTime.now(clock).minusMonths(1))
                    .createdBy(userId).payerUserId(userId)
                    .version(0L)
                    .build();
            entityManager.persist(c);
            entityManager.flush();
            return c.getId();
        });
    }

    /** 契約由来の権利行（valid_until は発行時 NULL＝無期限。D5 の出発点）。 */
    protected UUID insertEntitlement(Long userId, UUID contractId, String featureKey) {
        return transactionTemplate.execute(tx -> {
            EntitlementEntity e = EntitlementEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                    .featureKey(featureKey)
                    .sourceKind(EntitlementSourceKind.PLAN).sourceRefId(contractId)
                    .validFrom(LocalDateTime.now(clock).minusMonths(1))
                    .validUntil(null)
                    .build();
            entityManager.persist(e);
            entityManager.flush();
            return e.getId();
        });
    }

    /** DB の実値を読む（第一次キャッシュを通さない）。 */
    protected BillingContractEntity reloadContract(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        });
    }

    protected List<EntitlementEntity> reloadEntitlements(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return entitlementRepository.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.PLAN, contractId);
        });
    }

    /** テストごとの後始末（scope_id で限定して他クラスの検体を壊さない）。 */
    protected void cleanupScope(Long userId) {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("DELETE FROM entitlements WHERE scope_id = :id")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM active_billing_contract_operation_pointers WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id)")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_operations WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id)")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_payer_handover_requests WHERE scope_id = :id")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :id")
                    .setParameter("id", userId).executeUpdate();
        });
    }
}
