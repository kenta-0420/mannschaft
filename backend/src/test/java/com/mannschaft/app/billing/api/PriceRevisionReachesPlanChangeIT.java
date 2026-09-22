package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 試練隊（第3陣）H群 — 【到達 AC・必須】AC-127 の直接 IT。
 *
 * <p>陣立て書 H群「価格改定 API で登録（create）・Provision・Activate した価格が、
 * {@code BillingCurrentBandResolver} を経由して {@code BillingPlanChangePreviewService}（見積り）と
 * {@code BillingPlanChangeService}（確定）の両方から新価格として読めることを、E2E に近い IT で確認する」
 * を、①税コード登録 → ②{@code POST /price-revisions} → ③provision → ④activate → ⑤見積り → ⑥確定
 * の順に実機に近い形で通す。</p>
 *
 * <p>URL 文字列と JSON 構造だけを参照し、Java 型は {@code PlanRepository}/{@code PlanEntity}
 * 以外は直接 import しない（{@code AbstractBillingPlanChangeApiIT} と同じ流儀）。
 * {@code BillingPlanChangeGateway} は {@code @MockitoBean}（Stripe 通信はしない）。</p>
 *
 * <p>正本: `.claude/campaigns/price-rev-plan-v3.md` H群 AC-127。</p>
 */
@DisplayName("AC-127到達IT: price-revisions で登録した新価格がPR6b-1の見積り・確定から読める")
class PriceRevisionReachesPlanChangeIT extends AbstractBillingPlanChangeApiIT {

    private static final long SYSTEM_ADMIN_ID = 700_901L;
    private static final long NEW_TO_AMOUNT = 4_400L;

    /**
     * create 時点の {@code validateEffectivePeriod}（effectiveFrom が過去だと400）を満たしつつ、
     * activate 時点では確実に過ぎているようにするための実行バッファ。短すぎると
     * create→provision→activate の実処理時間だけで使い切ってしまいflakyになるため、
     * 実測（本IT実測: 3ステップで概ね数十〜数百ms）に対し十分な余裕を持たせる。
     */
    private static final Duration EFFECTIVE_FROM_BUFFER = Duration.ofMillis(800);

    @Autowired
    private PlanRepository planRepository;

    /**
     * {@code StripeBillingPriceProvisionGateway} は本コミット時点で {@code TEMP_STUB}
     * （全メソッドが {@link UnsupportedOperationException} を投げるだけの未実装実体）であり、
     * モックしないと provision が必ず PROVISION_FAILED になり、以降 activate も
     * STATE_CONFLICT（409）で必ず失敗する。{@code BillingPlanChangeGateway} と同じ流儀で
     * Stripe 通信をしない {@code @MockitoBean} に差し替える
     * （{@code BillingPriceProvisionGateway} の Javadoc が名指しする既存の単体テストと同じ対応）。
     */
    @MockitoBean
    private BillingPriceProvisionGateway priceProvisionGateway;

    @BeforeEach
    void setUp() {
        given(priceProvisionGateway.findPriceByMetadata(any(), any())).willReturn(Optional.empty());
        given(priceProvisionGateway.resolveOrCreateProduct(any())).willReturn(
                new BillingPriceProvisionGateway.ProductResolution("prod_ac127_full", true));
        given(priceProvisionGateway.createPrice(any())).willReturn(
                new BillingPriceProvisionGateway.PriceCreationResult("price_ac127_full"));

        // AbstractMySqlIntegrationTest は application-test.yml で flyway.enabled=false・
        // ddl-auto=create のため、V150 seed migration の plans マスタ行（FREE/BASIC/FULL）は
        // テスト DB に一切投入されない（スキーマのみ Hibernate が作る）。
        // price-revisions 作成 API は productKind=PLAN の場合 planRepository.existsById() で
        // 実在チェックをするため、band を直接 insertBand() する既存フィクスチャとは別に、
        // plans 行自体を明示的に用意する必要がある
        // （同型の対応: PriceRevisionOverlapConcurrencyIT が "FULL_IT" で同じことをしている）。
        if (planRepository.findById(TO_PLAN_KEY).isEmpty()) {
            planRepository.save(PlanEntity.builder().planKey(TO_PLAN_KEY).enabled(true)
                    .displayNameKey("k").descriptionKey("d").sortOrder(1).build());
        }

        // TO band は price-revisions API 経由で作る（insertBand による直接投入はしない）ため、
        // ここでは FROM 側の契約フィクスチャだけを親クラスの部品で組み立てる。
        userId = insertUser("ac127");
        subscriptionRef = "sub_ac127_" + userId;
        customerId = insertCustomer(userId);
        fromBandId = insertBand(FROM_PLAN_KEY, FROM_AMOUNT, com.mannschaft.app.billing.BillingPriceVersionStatus.ACTIVE,
                FROM_STRIPE_PRICE_REF, 1, null);
        contractId = insertContract(fromBandId, LocalDateTime.now(clock).plusDays(20).withNano(0));
        stubStripeQuote(STRIPE_QUOTED_AMOUNT);
        stubStripeApply("in_ac127_default_" + userId, "open", false);
    }

    @Test
    @DisplayName("AC-127: create→provision→activateした新価格がPR6b-1の見積り・確定に反映される")
    void newPriceRevisionFlowsIntoPlanChangePreviewAndConfirm() throws Exception {
        // ① 税コード登録
        registerTaxCode();

        // ② POST /price-revisions で新価格 revision 作成（TO_PLAN_KEYの新価格帯）
        MvcResult createResult = createPriceRevision();
        JsonNode createData = body(createResult).path("data");
        String revisionId = createData.path("id").asText();

        // ③ provision。lockVersion は「直前のレスポンスが返した現在値」を必ず使う
        // （固定0を送り続けると2回目以降のCAS呼び出しが必ず409 LOCK_VERSION_CONFLICTになる。
        // 根治治療として PriceRevisionResponse に lockVersion フィールドを追加したので、
        // それをそのまま使い回す）。
        long lockVersionAfterCreate = createData.path("lockVersion").asLong();
        MvcResult provisionResult = adminPost(
                "/api/v1/system-admin/billing/price-revisions/" + revisionId + "/provision",
                "{\"lockVersion\":" + lockVersionAfterCreate + "}")
                .andReturn();
        assertThat(provisionResult.getResponse().getStatus())
                .as("provisionが失敗している(body=%s)", provisionResult.getResponse().getContentAsString())
                .isEqualTo(200);
        long lockVersionAfterProvision = body(provisionResult).path("data").path("lockVersion").asLong();

        // ④ activate（即時 ACTIVE）。PriceRevisionActivationService#activate は
        // 「effectiveFrom が activate 実行時点の実時刻を超えていない」ときのみ ACTIVE に直接遷移させ、
        // 超えていれば SCHEDULED に留める（決定4・AC-105〜110）。createPriceRevision() で
        // effectiveFrom に与えた「作成時刻+実行バッファ」を使い切るため、activate 呼び出し前に
        // バッファ分だけ待ち、確実に「今は effectiveFrom を過ぎている」状態にしてから叩く。
        Thread.sleep(EFFECTIVE_FROM_BUFFER.plusMillis(200).toMillis());
        MvcResult activateResult = adminPost(
                "/api/v1/system-admin/billing/price-revisions/" + revisionId + "/activate",
                "{\"lockVersion\":" + lockVersionAfterProvision + "}")
                .andReturn();
        assertThat(activateResult.getResponse().getStatus())
                .as("activateが失敗している(body=%s)", activateResult.getResponse().getContentAsString())
                .isEqualTo(200);

        // ⑤ PR6b-1の見積りAPIが新しいinputAmount/taxAmountを返す
        MvcResult previewResult = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andReturn();
        JsonNode previewData = body(previewResult).path("data");
        assertThat(previewResult.getResponse().getStatus())
                .as("見積りAPIが新価格を解決できず失敗している(status=%d, body=%s)",
                        previewResult.getResponse().getStatus(),
                        previewResult.getResponse().getContentAsString())
                .isEqualTo(201);
        UUID previewId = UUID.fromString(previewData.path("previewId").asText());

        // ⑥ 確定APIがそのbandを用いてBillingContractChangeを作成する
        ResultActions changeResult = change(userId, contractId, previewId, contractVersion(), newKey());
        int changeStatus = changeResult.andReturn().getResponse().getStatus();
        assertThat(changeStatus)
                .as("確定APIがACTIVE化した新価格bandで契約変更を作成できていない")
                .isEqualTo(202);
        assertThat(changesOf()).isNotEmpty();
    }

    /**
     * 税コードを登録する。{@code code} 自身が {@code PriceBandInput.taxCode} で参照するキーであり、
     * 生成された id ではない（実 DTO: {@code PriceRevisionCreateRequest} は taxCodeId を持たず、
     * band ごとに {@code taxCode}（code 文字列）と {@code taxBehavior} を持つ）。
     */
    private void registerTaxCode() throws Exception {
        // enabled は BillingTaxCodeCreateRequest ではプリミティブ boolean のため、
        // JSON で省略すると欠損値として false にデシリアライズされ、resolveEffective の
        // isEnabled() フィルタで無効扱いになる（TAX_CODE_NOT_FOUND）。明示的に true を渡す。
        MvcResult result = adminPost("/api/v1/system-admin/billing/tax-codes",
                "{\"code\":\"AC127_TAX\",\"displayName\":\"AC-127検体税\",\"stripeTaxCode\":\"txcd_ac127\","
                        + "\"rateBasisPoints\":1000,\"validFrom\":\"2020-01-01T00:00:00Z\",\"enabled\":true}")
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("税コード登録が失敗している(body=%s)", result.getResponse().getContentAsString())
                .isEqualTo(201);
    }

    private MvcResult createPriceRevision() throws Exception {
        // 実 DTO の validateEffectivePeriod は effectiveFrom.isBefore(now) を 400 で拒否するため、
        // 過去日時は使えない。一方 PriceRevisionActivationService#activate は
        // effectiveFrom が「activate 実行時点の実時刻」を超えていれば即時 ACTIVE にせず SCHEDULED に
        // 留める。2099年のような遠い未来を使うと恒久的に SCHEDULED のままになり見積り/確定 API から
        // 読めなくなる（実測で確認済み）ため、「create 時点ではわずかに未来・activate 時点では
        // 既に過去」になる実時刻ベースの値を使う（EFFECTIVE_FROM_BUFFER 分だけ待ってから activate する）。
        Instant effectiveFrom = Instant.now(clock).plus(EFFECTIVE_FROM_BUFFER);
        String requestBody = "{"
                + "\"productKind\":\"PLAN\",\"productKey\":\"" + TO_PLAN_KEY + "\","
                + "\"scopeKind\":\"USER\","
                + "\"effectiveFrom\":\"" + effectiveFrom + "\","
                + "\"effectiveUntil\":null,"
                + "\"bands\":[{\"bandNo\":1,\"minMembers\":1,\"maxMembers\":null,"
                + "\"inputAmount\":" + NEW_TO_AMOUNT + ","
                + "\"taxBehavior\":\"EXCLUSIVE\",\"taxCode\":\"AC127_TAX\"}]"
                + "}";
        MvcResult result = adminPost("/api/v1/system-admin/billing/price-revisions", requestBody).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("price-revisions 作成が失敗している(body=%s)", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return result;
    }

    private ResultActions adminPost(String path, String jsonBody) throws Exception {
        return mockMvc.perform(post(path)
                .with(user(String.valueOf(SYSTEM_ADMIN_ID)).roles("SYSTEM_ADMIN"))
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody));
    }
}
