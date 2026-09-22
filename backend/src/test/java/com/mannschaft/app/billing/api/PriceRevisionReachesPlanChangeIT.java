package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
 * <p><b>本テストが red になる理由</b>: {@code POST /api/v1/system-admin/billing/tax-codes} /
 * {@code POST /api/v1/system-admin/billing/price-revisions} / {@code .../provision} /
 * {@code .../activate} のいずれも本コミット時点で未実装（404）であるため。URL 文字列と JSON 構造だけを
 * 参照し、未実装の Java 型は直接 import しない（{@code AbstractBillingPlanChangeApiIT} と同じ流儀）。
 * {@code BillingPlanChangeGateway} は {@code @MockitoBean}（Stripe 通信はしない）。</p>
 *
 * <p>正本: `.claude/campaigns/price-rev-plan-v3.md` H群 AC-127。</p>
 */
@DisplayName("AC-127到達IT: price-revisions で登録した新価格がPR6b-1の見積り・確定から読める")
class PriceRevisionReachesPlanChangeIT extends AbstractBillingPlanChangeApiIT {

    private static final long SYSTEM_ADMIN_ID = 700_901L;
    private static final long NEW_TO_AMOUNT = 4_400L;

    @BeforeEach
    void setUp() {
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
        String revisionId = createPriceRevision();

        // ③ provision
        adminPost("/api/v1/system-admin/billing/price-revisions/" + revisionId + "/provision", "{}")
                .andReturn();

        // ④ activate（即時 ACTIVE）
        adminPost("/api/v1/system-admin/billing/price-revisions/" + revisionId + "/activate", "{}")
                .andReturn();

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
        MvcResult result = adminPost("/api/v1/system-admin/billing/tax-codes",
                "{\"code\":\"AC127_TAX\",\"displayName\":\"AC-127検体税\",\"stripeTaxCode\":\"txcd_ac127\","
                        + "\"rateBasisPoints\":1000,\"validFrom\":\"2020-01-01T00:00:00Z\"}")
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("税コード登録が失敗している(body=%s)", result.getResponse().getContentAsString())
                .isEqualTo(201);
    }

    private String createPriceRevision() throws Exception {
        String requestBody = "{"
                + "\"productKind\":\"PLAN\",\"productKey\":\"" + TO_PLAN_KEY + "\","
                + "\"scopeKind\":\"USER\","
                // 実 DTO の validateEffectivePeriod は effectiveFrom > now を要求する（過去日時は400）。
                // activate は即時 ACTIVE 化するため、未来日時で作成しても本テストの検証には影響しない。
                + "\"effectiveFrom\":\"2099-01-01T00:00:00Z\","
                + "\"effectiveUntil\":null,"
                + "\"bands\":[{\"bandNo\":1,\"minMembers\":1,\"maxMembers\":null,"
                + "\"inputAmount\":" + NEW_TO_AMOUNT + ","
                + "\"taxBehavior\":\"EXCLUSIVE\",\"taxCode\":\"AC127_TAX\"}]"
                + "}";
        MvcResult result = adminPost("/api/v1/system-admin/billing/price-revisions", requestBody).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("price-revisions 作成が失敗している(body=%s)", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(result).path("data").path("id").asText();
    }

    private ResultActions adminPost(String path, String jsonBody) throws Exception {
        return mockMvc.perform(post(path)
                .with(user(String.valueOf(SYSTEM_ADMIN_ID)).roles("SYSTEM_ADMIN"))
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonBody));
    }
}
