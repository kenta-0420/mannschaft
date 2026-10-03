package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.ActiveBillingContractOperationPointerEntity;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerRepository;
import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingOperationActorKind;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.BillingOperationStep;
import com.mannschaft.app.billing.BillingPlanChangeGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Billing Center PR6b-1 — C群「3DS」試練（AC-48〜73）の共通基盤（試練・red）。
 *
 * <h2>第8隊への発注書（このクラス群が要求する契約）</h2>
 * <pre>
 * GET /api/v1/me/billing/contracts/{contractId}/changes/{changeId}/payment-action
 * </pre>
 * <ul>
 *   <li>change が {@code REQUIRES_ACTION} のときだけ 200。本文は {@code ApiResponse} 包みで
 *       {@code $.data.paymentAction} が {@code {type, clientSecret, expiresAt}}（AC-48）。</li>
 *   <li>{@code PENDING_PAYMENT} / {@code APPLIED} / {@code FAILED} / change 不在は
 *       409 {@code ENTITLEMENT_021}（CHANGE_CONFLICT・AC-49〜52）。ただし
 *       <b>他人が起票した change は 404</b>（存在オラクルを残さない・E5'・AC-70）。</li>
 *   <li>{@code clientSecret} は {@link BillingPlanChangeGateway#retrievePaymentAction} で
 *       <b>都度取得</b>し、DB に保存しない（AC-54/AC-57）。</li>
 *   <li>リダイレクト型 3DS のために、200 のときだけ HttpOnly cookie
 *       {@code billing_payment_action_state} を
 *       {@code Path=/billing/payment-action/return} で発行する（E3'・AC-60）。</li>
 * </ul>
 *
 * <p><b>空虚な緑への備え</b>: 「実装が無いから何も起きない＝条件を満たす」形の緑を避けるため、
 * 否定検証（cookie を返さない・Stripe を呼ばない）には必ず陽性対照（200 のときは実際に
 * cookie を返し Stripe を呼ぶ）を対で置く。陽性対照が赤であるうちは、否定側の緑を通過の
 * 根拠にしてはならない。</p>
 *
 * <p>座席は試練A の {@link AbstractBillingPlanChangeApiIT} を再利用する（{@code @MockitoBean} の
 * 集合が同一になり TestContext Cache を分裂させない）。</p>
 */
abstract class AbstractBillingPaymentActionApiIT extends AbstractBillingPlanChangeApiIT {

    /** 第8隊が実装する唯一の payment-action パス。 */
    protected static final String PAYMENT_ACTION_PATH =
            "/api/v1/me/billing/contracts/%s/changes/%s/payment-action";

    /** E3'（AC-60）: 既存 {@code billing_return_state} とは<b>別名</b>の cookie。 */
    protected static final String PAYMENT_ACTION_COOKIE = "billing_payment_action_state";
    /** 既存 Checkout / Portal の退避 cookie（AC-63 の共存検体で使う）。 */
    protected static final String CHECKOUT_COOKIE = "billing_return_state";
    /** E3'（AC-60）: cookie の path は戻り口そのものに限定する。 */
    protected static final String PAYMENT_ACTION_COOKIE_PATH = "/billing/payment-action/return";
    /** 3DS の戻り口（AC-66〜69 の回帰）。 */
    protected static final String RETURN_PATH = "/billing/payment-action/return";

    protected static final String CLIENT_SECRET = "pi_dummy_secret_aaaaaaaaaaaaaaaa";
    /** 差額請求の Invoice（change 行に記録済みの検体）。 */
    protected static final String INVOICE_REF_PREFIX = "in_pr6b1_payment_action";

    @Autowired protected BillingContractChangeRepository changeRepository;
    @Autowired protected ActiveBillingContractOperationPointerRepository pointerRepository;

    // ============================================================
    // リクエスト
    // ============================================================

    /** payment-action を要求する。 */
    protected ResultActions paymentAction(long actorId, UUID targetContractId, UUID changeId)
            throws Exception {
        return mockMvc.perform(get(String.format(PAYMENT_ACTION_PATH, targetContractId, changeId))
                .with(user(String.valueOf(actorId))));
    }

    // ============================================================
    // Stripe 呼び出しの観測
    // ============================================================

    /**
     * Stripe 窓口（2 ポート）への呼び出し<b>総数</b>（メソッド名に依存しない）。
     *
     * <p>AC-53 / AC-70 は「Stripe retrieve が 0 回」を要求する。実装がどちらのポートの
     * どのメソッドで取りに行っても取りこぼさないよう総数で測り、陽性対照（200 で 1 回以上）を
     * 対で置く。</p>
     */
    protected long gatewayInvocations() {
        return org.mockito.Mockito.mockingDetails(planChangeGateway).getInvocations().size()
                + org.mockito.Mockito.mockingDetails(billingPaymentGateway).getInvocations().size();
    }

    /** 3DS の client secret を Stripe が返す検体を仕込む。 */
    protected void stubPaymentAction(Instant expiresAt) {
        org.mockito.BDDMockito.given(planChangeGateway.retrievePaymentAction(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .willReturn(Optional.of(new BillingPlanChangeGateway.PaymentAction(
                        "payment_intent", CLIENT_SECRET, expiresAt)));
    }

    // ============================================================
    // raw ヘッダの観測（AC-60〜65 は Set-Cookie / Cookie を生で測る）
    // ============================================================

    /** 応答の生 {@code Set-Cookie} ヘッダをすべて返す。 */
    protected List<String> rawSetCookies(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie");
    }

    /** 指定 cookie 名の生 {@code Set-Cookie} 行（無ければ null）。 */
    protected String rawSetCookie(MvcResult result, String name) {
        return rawSetCookies(result).stream()
                .filter(h -> h.startsWith(name + "="))
                .findFirst()
                .orElse(null);
    }

    /** 生 {@code Set-Cookie} 行から属性値を取り出す（{@code Max-Age} 等・無ければ null）。 */
    protected String cookieAttribute(String setCookieLine, String attributeName) {
        for (String part : setCookieLine.split(";")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, attributeName + "=", 0, attributeName.length() + 1)) {
                return trimmed.substring(attributeName.length() + 1);
            }
        }
        return null;
    }

    /** 生 {@code Set-Cookie} 行から cookie 値（token）を取り出す。 */
    protected String cookieValue(String setCookieLine) {
        int eq = setCookieLine.indexOf('=');
        int semi = setCookieLine.indexOf(';');
        return setCookieLine.substring(eq + 1, semi < 0 ? setCookieLine.length() : semi);
    }

    /**
     * return state token 自身の {@code exp}（epoch 秒）を取り出す（AC-62）。
     *
     * <p>token は {@code {kid}.{base64url payload}.{signature}} で、payload は
     * {@code BillingReturnStateService} が {@code |} 区切りで並べた 11 フィールド。
     * 10 番目（index 9）が {@code exp} である。<b>署名は検証しない</b>——ここで見たいのは
     * 「cookie の Max-Age だけ短く、token だけ長命」という検体が作られていないかだけである。</p>
     */
    protected long tokenExpEpochSecond(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new AssertionError("return state token の形が {kid}.{payload}.{sig} ではない: " + token);
        }
        String raw = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String[] fields = raw.split("\\|", -1);
        if (fields.length != 11) {
            throw new AssertionError("return state payload のフィールド数が 11 ではない: " + fields.length);
        }
        return Long.parseLong(fields[9]);
    }

    // ============================================================
    // フィクスチャ
    // ============================================================

    /**
     * PLAN_CHANGE の operation ＋ pointer を作る（E1F: 支払い待ちの間も pointer を保持する）。
     *
     * <p>{@code seedUpgradableContract} の後に呼ぶこと。</p>
     *
     * @return operationId
     */
    protected UUID insertPlanChangeOperation() {
        return transactionTemplate.execute(tx -> {
            BillingContractOperationEntity op = BillingContractOperationEntity.builder()
                    .contractId(contractId)
                    .billingCustomerId(customerId)
                    .kind(BillingOperationKind.PLAN_CHANGE)
                    .status(BillingOperationStatus.CALLING_STRIPE)
                    .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .requestHash("0".repeat(64))
                    .stripeSubscriptionRef(subscriptionRef)
                    .version(0L)
                    .actorKind(BillingOperationActorKind.USER)
                    .createdBy(userId)
                    .build();
            entityManager.persist(op);
            entityManager.persist(ActiveBillingContractOperationPointerEntity.builder()
                    .contractId(contractId).operationId(op.getId()).build());
            entityManager.flush();
            return op.getId();
        });
    }

    /**
     * PLAN_CHANGE の operation だけを作る（pointer は張らない）。
     *
     * <p>{@code billing_contract_changes} は {@code uk_bcc_operation (operation_id)} と
     * {@code uk_bcc_idempotency (contract_id, idempotency_key)} で operation と 1:1 に縛られている。
     * したがって<b>1つのテストで複数の change 検体を並べる場合、operation も検体ごとに要る</b>
     * （同じ operationId を使い回すと 2件目の INSERT が一意制約で落ち、assert に到達しない）。
     * pointer は contract_id が主キーで契約あたり 1 行しか置けないため、ここでは張らない
     * （payment-action は pointer を見ない。lease は {@link #insertPlanChangeOperation} が張る 1 本で足りる）。</p>
     *
     * @return operationId
     */
    protected UUID insertPlanChangeOperationWithoutPointer() {
        return transactionTemplate.execute(tx -> {
            BillingContractOperationEntity op = BillingContractOperationEntity.builder()
                    .contractId(contractId)
                    .billingCustomerId(customerId)
                    .kind(BillingOperationKind.PLAN_CHANGE)
                    .status(BillingOperationStatus.CALLING_STRIPE)
                    .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .requestHash("0".repeat(64))
                    .stripeSubscriptionRef(subscriptionRef)
                    .version(0L)
                    .actorKind(BillingOperationActorKind.USER)
                    .createdBy(userId)
                    .build();
            entityManager.persist(op);
            entityManager.flush();
            return op.getId();
        });
    }

    /**
     * upgrade の change 行を作る。
     *
     * @param operationId            1:1 で結ぶ operation
     * @param status                 change の状態（AC-48〜52 の検体）
     * @param createdBy              起票者（AC-70 の「別 actor が起票した change」検体はここを変える）
     * @param pendingUpdateExpiresAt 3DS の期限（AC-61 の cookie 期限の根拠・null 可）
     */
    protected UUID insertChange(UUID operationId, BillingContractChangeStatus status, Long createdBy,
                                Instant pendingUpdateExpiresAt) {
        return transactionTemplate.execute(tx -> {
            BillingContractChangeEntity change = BillingContractChangeEntity.builder()
                    .operationId(operationId)
                    .contractId(contractId)
                    .billingCustomerId(customerId)
                    .kind(BillingContractChangeKind.UPGRADE)
                    .status(status)
                    .fromPlanKey(FROM_PLAN_KEY)
                    .toPlanKey(TO_PLAN_KEY)
                    .fromPriceBandVersionId(fromBandId)
                    .toPriceBandVersionId(toBandId)
                    .fromAmountIncludingTax(FROM_AMOUNT)
                    .toAmountIncludingTax(TO_AMOUNT)
                    .stripeSubscriptionRef(subscriptionRef)
                    .stripeInvoiceRef(INVOICE_REF_PREFIX + "_" + UUID.randomUUID())
                    .pendingUpdateExpiresAt(pendingUpdateExpiresAt)
                    .pendingUpdateTargetSnapshot("{\"priceRef\":\"" + TO_STRIPE_PRICE_REF + "\"}")
                    .effectiveAt(Instant.now(clock))
                    .idempotencyKey(operationId.toString())
                    .requestHash("0".repeat(64))
                    .version(0L)
                    .createdBy(createdBy)
                    .build();
            entityManager.persist(change);
            entityManager.flush();
            return change.getId();
        });
    }

    /** DB の実値を読む（第一次キャッシュを通さない）。 */
    protected BillingContractChangeEntity reloadChange(UUID changeId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return changeRepository.findByIdAndDeletedAtIsNull(changeId)
                    .orElseThrow(() -> new AssertionError("change 行が無い: " + changeId));
        });
    }

    /** 件数を数える（型推論を避けるため必ず {@link Long} で受ける）。 */
    protected long countRows(String sql) {
        Long count = transactionTemplate.execute(tx ->
                ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
        return count == null ? 0L : count;
    }

    /**
     * billing 系の<b>全テーブルの全文字列列</b>を走査し、与えた秘密が残っている件数を返す（AC-57）。
     *
     * <p>列名を列挙して照合すると、実装が別の列（例えば {@code pending_update_target_snapshot} の中）へ
     * 忍ばせた場合に取りこぼす。information_schema から列を引いて総当りする。</p>
     */
    protected long countBillingColumnsContaining(String secret) {
        Long hits = transactionTemplate.execute(tx -> {
            @SuppressWarnings("unchecked")
            List<Object[]> columns = entityManager.createNativeQuery("""
                    SELECT table_name, column_name FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name LIKE 'billing%'
                       AND data_type IN ('varchar','char','text','mediumtext','longtext','json')
                    """).getResultList();
            long found = 0L;
            for (Object[] row : columns) {
                String table = String.valueOf(row[0]);
                String column = String.valueOf(row[1]);
                Number count = (Number) entityManager.createNativeQuery(
                                "SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "` LIKE :needle")
                        .setParameter("needle", "%" + secret + "%")
                        .getSingleResult();
                found += count.longValue();
            }
            return found;
        });
        return hits == null ? 0L : hits;
    }
}
