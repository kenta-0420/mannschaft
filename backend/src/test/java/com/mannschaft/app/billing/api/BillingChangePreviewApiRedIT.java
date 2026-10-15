package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingChangePreviewEntity;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — A群 事前見積りの発行（AC-1〜5 / AC-17〜20 / AC-22〜24・試練 red）。
 *
 * <p>消費（AC-6〜12 / AC-21）は {@link BillingChangePreviewConsumeRedIT}、再照合（AC-13〜16）は
 * {@link BillingChangePreviewRevalidationRedIT} が担う。</p>
 *
 * <p><b>空虚な緑への備え</b>: 否定側（409 になる／Stripe を呼ばない）の検体には、必ず同じ土台の
 * 陽性対照（AC-1 の 201 と {@code previewPlanChange} 1回）を対で置いている。AC-1 が赤であるうちは、
 * 否定側の緑を「通った」と読んではならない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 事前見積り API（A群 AC-1〜24・試練 red）")
class BillingChangePreviewApiRedIT extends AbstractBillingPlanChangeApiIT {

    @BeforeEach
    void setUp() {
        seedUpgradableContract("preview");
    }

    @AfterEach
    void tearDown() {
        cleanupScope();
    }

    // ═════════ AC-1 / AC-3: 正常系（陽性対照そのもの） ═════════

    @Test
    @DisplayName("AC-1: change-previews は 201 で previewId / kind / amountDueNow / effectiveAt / expiresAt を返す")
    void AC01_見積りは201で5項目を返す() throws Exception {
        MvcResult result = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.previewId").isNotEmpty())
                .andExpect(jsonPath("$.data.kind").value("UPGRADE"))
                .andExpect(jsonPath("$.data.amountDueNow").isMap())
                .andExpect(jsonPath("$.data.effectiveAt").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andReturn();

        assertThat(planChangeCalls("previewPlanChange"))
                .as("見積りは Stripe へ一度だけ問い合わせる（呼ばずに 201 を返す空虚な緑を排除）")
                .isEqualTo(1L);
        assertThat(body(result).path("data").path("amountDueNow").path("currency").asText())
                .isEqualTo("JPY");
    }

    @Test
    @DisplayName("AC-3: previewId は UUID である（opaque token ではない）")
    void AC03_previewIdはUUID() throws Exception {
        MvcResult result = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isCreated()).andReturn();

        String previewId = body(result).path("data").path("previewId").asText();
        UUID parsed = UUID.fromString(previewId); // UUID でなければここで例外＝赤
        assertThat(reloadPreview(parsed).getId())
                .as("返した previewId が DB の主キーそのものであること").isEqualTo(parsed);
    }

    // ═════════ AC-2: 金額の出所は Stripe（自前で日割りしない） ═════════

    @Test
    @DisplayName("AC-2: amountDueNow は Stripe の見積り API が返した値そのもの（こちらで日割り計算しない）")
    void AC02_金額はStripeの戻り値そのまま() throws Exception {
        // 777 は band 金額（1200 / 3300）からどんな日割り式でも導けない値。
        // 自前計算していれば必ず別の値になり、このテストは落ちる。
        stubStripeQuote(777L);
        MvcResult first = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isCreated()).andReturn();
        assertThat(body(first).path("data").path("amountDueNow").path("amountIncludingTax").asLong())
                .as("Stripe が 777 と言ったら 777 を返す").isEqualTo(777L);

        // 陽性対照: 戻り値を変えたら応答も変わる（777 を定数で埋め込んだ実装を排除する）。
        stubStripeQuote(4_321L);
        MvcResult second = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isCreated()).andReturn();
        assertThat(body(second).path("data").path("amountDueNow").path("amountIncludingTax").asLong())
                .as("Stripe の戻り値に追随する（ハードコードでない）").isEqualTo(4_321L);
    }

    // ═════════ AC-4: NOT NULL 列をすべて埋める ═════════

    @Test
    @DisplayName("AC-4: billing_change_previews の NOT NULL 列（税・金額・期間・按分・人数・from/to band）をすべて埋める")
    void AC04_NOTNULL列をすべて埋める() throws Exception {
        MvcResult result = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isCreated()).andReturn();
        BillingChangePreviewEntity row = reloadPreview(
                UUID.fromString(body(result).path("data").path("previewId").asText()));

        assertThat(row.getTaxSnapshot()).as("tax_snapshot は NOT NULL").isNotBlank();
        assertThat(row.getAmountSnapshot()).as("amount_snapshot は NOT NULL").isNotBlank();
        assertThat(row.getPeriodStart()).as("period_start は NOT NULL").isNotNull();
        assertThat(row.getPeriodEnd()).as("period_end は NOT NULL").isNotNull();
        assertThat(row.getProrationAt()).as("proration_at は NOT NULL").isNotNull();
        assertThat(row.getMemberCount()).as("人数band課金の根拠。null のままにしない").isNotNull();
        assertThat(row.getFromPriceBandVersionId()).as("from band は NOT NULL").isEqualTo(fromBandId);
        assertThat(row.getToPriceBandVersionId()).as("to band は NOT NULL").isEqualTo(toBandId);
        assertThat(row.getActorId()).isEqualTo(userId);
        assertThat(row.getContractId()).isEqualTo(contractId);
        assertThat(row.getBillingCustomerId()).isEqualTo(customerId);
        assertThat(row.getContractVersion()).isEqualTo(contractVersion());
        assertThat(row.getRequestHash()).as("request_hash は SHA-256 hex 64桁").hasSize(64);
        assertThat(row.getConsumedAt()).as("発行直後は未消費").isNull();
    }

    // ═════════ AC-5: 有効期限は最大10分 ═════════

    @Test
    @DisplayName("AC-5: preview の有効期限は最大10分（発行時刻 + 10分 を超えない）")
    void AC05_有効期限は最大10分() throws Exception {
        Instant before = Instant.now();
        MvcResult result = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isCreated()).andReturn();
        BillingChangePreviewEntity row = reloadPreview(
                UUID.fromString(body(result).path("data").path("previewId").asText()));

        assertThat(row.getExpiresAt())
                .as("10分を超える長命な preview を作らない")
                .isBeforeOrEqualTo(before.plus(Duration.ofMinutes(10)).plusSeconds(5));
        assertThat(row.getExpiresAt())
                .as("発行直後に既に失効している preview も作らない（境界の向きの陽性対照）")
                .isAfter(before);
    }

    // ═════════ AC-17: client が価格の同定に介入できない ═════════

    @Test
    @DisplayName("AC-17: client が priceVersionId / priceBandVersionId を送っても採用されない（server が tx 内で確定）")
    void AC17_クライアントは価格IDを送れない() throws Exception {
        UUID attackerBand = insertBand(TO_PLAN_KEY, 1L, BillingPriceVersionStatus.ACTIVE,
                "price_attacker", 1, null);

        MvcResult result = previewRaw(userId, contractId, newKey(),
                "{\"toProductKind\":\"PLAN\",\"toProductKey\":\"" + TO_PLAN_KEY + "\",\"version\":"
                        + contractVersion() + ",\"priceBandVersionId\":\"" + attackerBand
                        + "\",\"priceVersionId\":\"" + UUID.randomUUID() + "\"}").andReturn();

        int statusCode = result.getResponse().getStatus();
        assertThat(statusCode)
                .as("未知項目を 400 で弾くか、無視して 201 にするかは実装の自由。500 だけは許さない")
                .isIn(201, 400);
        if (statusCode == 201) {
            BillingChangePreviewEntity row = reloadPreview(
                    UUID.fromString(body(result).path("data").path("previewId").asText()));
            assertThat(row.getToPriceBandVersionId())
                    .as("client が指定した band（1円）ではなく server が解決した band を使う")
                    .isEqualTo(toBandId);
        }
    }

    // ═════════ AC-18: 月末境界 ═════════

    @Test
    @DisplayName("AC-18: 期末まで 30分+60秒 未満なら 409 ENTITLEMENT_022 と availableAt を返す")
    void AC18_月末境界は409() throws Exception {
        setPeriodEnd(LocalDateTime.now(clock).plusMinutes(20));

        preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_022"))
                .andExpect(jsonPath("$.error.details.availableAt").isNotEmpty());

        assertThat(planChangeCalls("previewPlanChange"))
                .as("月末境界では Stripe へ問い合わせない").isZero();
    }

    @Test
    @DisplayName("AC-18(陽性対照): 期末まで十分あるなら 201 で見積りできる（常時 409 でないこと）")
    void AC18b_境界外は201() throws Exception {
        setPeriodEnd(LocalDateTime.now(clock).plusDays(15));

        preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isCreated());
    }

    // ═════════ AC-19: price_band_version_id が NULL の既存契約 ═════════

    @Test
    @DisplayName("AC-19: price_band_version_id が NULL の既存契約は、server が人数から band を解決して埋める")
    void AC19_band_NULLの契約はサーバーが解決する() throws Exception {
        setContractBandToNull();

        MvcResult result = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isCreated()).andReturn();

        BillingChangePreviewEntity row = reloadPreview(
                UUID.fromString(body(result).path("data").path("previewId").asText()));
        assertThat(row.getFromPriceBandVersionId())
                .as("V196 の from band は NOT NULL。人数から解決して埋める")
                .isEqualTo(fromBandId);
    }

    @Test
    @DisplayName("AC-19: band を解決できなければ 409 CHANGE_CONFLICT（500 にしない）")
    void AC19b_band解決不能は409で500にしない() throws Exception {
        setContractBandToNull();
        // 現在の人数(1)ではどの band にも当たらない世界にする。
        mutateBand(fromBandId, band -> {
            band.setMinMembers(50);
            band.setMaxMembers(100);
        });

        MvcResult result = preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_021"))
                .andExpect(jsonPath("$.error.details.reason").value("CHANGE_CONFLICT"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("NOT NULL 制約違反を 500 として漏らさない").isNotEqualTo(500);
    }

    // ═════════ AC-20: target band が使えない3検体 ═════════

    @Test
    @DisplayName("AC-20a: target band が不在なら 409 CHANGE_CONFLICT")
    void AC20a_target_band不在は409() throws Exception {
        mutateBand(toBandId, band -> band.setDeletedAt(Instant.now()));

        preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("CHANGE_CONFLICT"));
        assertThat(planChangeCalls("previewPlanChange")).as("使えない band で Stripe を呼ばない").isZero();
    }

    @Test
    @DisplayName("AC-20b: target band が非 ACTIVE（RETIRED）なら 409")
    void AC20b_target_band非ACTIVEは409() throws Exception {
        mutateBand(toBandId, band -> band.setStatus(BillingPriceVersionStatus.RETIRED));

        preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("CHANGE_CONFLICT"));
        assertThat(planChangeCalls("previewPlanChange")).isZero();
    }

    @Test
    @DisplayName("AC-20c: target band の Stripe Price ref が null なら 409")
    void AC20c_StripePriceRef不在は409() throws Exception {
        mutateBand(toBandId, band -> band.setStripePriceRef(null));

        preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("CHANGE_CONFLICT"));
        assertThat(planChangeCalls("previewPlanChange"))
                .as("Price ref が無いまま Stripe を呼んではならない").isZero();
    }

    // ═════════ AC-22 / AC-23 / AC-24: upgrade として扱えない target ═════════

    @Test
    @DisplayName("AC-22: 同一プランを upgrade として要求したら 409")
    void AC22_同一プランは409() throws Exception {
        preview(userId, contractId, FROM_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isConflict());
        assertThat(planChangeCalls("previewPlanChange")).isZero();
    }

    @Test
    @DisplayName("AC-23: 下位プラン（安い band）を upgrade として要求したら 409（downgrade は PR6b-2）")
    void AC23_下位プランは409() throws Exception {
        // FULL を BASIC より安くして「下位」にする。
        mutateBand(toBandId, band -> {
            band.setAmountIncludingTax(FROM_AMOUNT - 500L);
            band.setInputAmount(FROM_AMOUNT - 500L);
        });

        preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isConflict());
        assertThat(planChangeCalls("previewPlanChange")).isZero();
    }

    @Test
    @DisplayName("AC-24: 同額の target は 409（金額が上がらないものを upgrade として扱わない）")
    void AC24_同額は409() throws Exception {
        mutateBand(toBandId, band -> {
            band.setAmountIncludingTax(FROM_AMOUNT);
            band.setInputAmount(FROM_AMOUNT);
        });

        preview(userId, contractId, TO_PLAN_KEY, contractVersion(), newKey())
                .andExpect(status().isConflict());
        assertThat(planChangeCalls("previewPlanChange")).isZero();
    }

    // ═════════ ヘルパ ═════════

    private void setPeriodEnd(LocalDateTime periodEnd) {
        transactionTemplate.executeWithoutResult(tx -> {
            var contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
            contract.setCurrentPeriodEnd(periodEnd.withNano(0));
            entityManager.flush();
        });
    }

    private void setContractBandToNull() {
        transactionTemplate.executeWithoutResult(tx -> {
            var contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
            contract.setPriceBandVersionId(null);
            entityManager.flush();
        });
    }
}
