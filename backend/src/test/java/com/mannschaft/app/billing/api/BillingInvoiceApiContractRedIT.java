package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.1 Billing Center PR5 — 課金履歴 API の <b>ページング・明細・監査</b>受け入れテスト（試練・red）。
 *
 * <p>対象 AC: AC-48 / AC-49 / AC-50 / AC-51 / AC-54 / AC-60。
 * 正本: {@code docs/features/F20.1_entitlement_billing/05_billing_center.md} §7・§8。</p>
 *
 * <p><b>実装は未着手</b>。URL 文字列と JSON 構造だけを観測するのでコンパイルは通り、
 * 「実行されて期待と違う」赤になる。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 課金履歴 API ページング・明細・監査（試練 red）")
class BillingInvoiceApiContractRedIT extends AbstractMySqlIntegrationTest {

    private static final String INVOICES = "/api/v1/me/billing/invoices";

    private static final long OWNER_ID = 700_201L;
    /** invoice を1件も持たない actor（AC-48 の 0 件検体）。 */
    private static final long EMPTY_OWNER_ID = 700_202L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BillingInvoiceJpaRepository invoiceRepository;
    @Autowired
    private BillingInvoiceLineJpaRepository lineRepository;
    @Autowired
    private BillingInvoiceAdjustmentJpaRepository adjustmentRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** period_end が同一の2件・null の1件を含む5件（並び順の決定性を測る検体）。 */
    private final List<UUID> ownerInvoiceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        adjustmentRepository.deleteAll();
        lineRepository.deleteAll();
        invoiceRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM audit_logs WHERE event_type = ?", "BILLING_INVOICE_VIEWED");
        ownerInvoiceIds.clear();

        // period_end: 6月末, 7月末, 7月末（同値2件）, 8月末, null
        List<Instant> periodEnds = new ArrayList<>();
        periodEnds.add(Instant.parse("2026-06-30T15:00:00Z"));
        periodEnds.add(Instant.parse("2026-07-31T15:00:00Z"));
        periodEnds.add(Instant.parse("2026-07-31T15:00:00Z"));
        periodEnds.add(Instant.parse("2026-08-31T15:00:00Z"));
        periodEnds.add(null);
        for (int i = 0; i < periodEnds.size(); i++) {
            BillingInvoiceEntity saved = invoiceRepository.save(BillingInvoiceApiTestFixtures.invoice(
                    EntitlementScopeKind.USER,
                    OWNER_ID,
                    UUID.randomUUID(),
                    "in_contract_" + UUID.randomUUID(),
                    periodEnds.get(i),
                    1_000L * (i + 1)));
            ownerInvoiceIds.add(saved.getId());
        }
    }

    // ═════════ 空・サイズ境界 ═════════

    @Test
    @DisplayName("AC48_0件でも200と空配列（500やnullにしない）")
    void AC48_0件は200と空配列() throws Exception {
        MvcResult result = mockMvc.perform(get(INVOICES)
                        .with(user(String.valueOf(EMPTY_OWNER_ID)))
                        .param("scopeKind", "USER")
                        .param("scopeId", String.valueOf(EMPTY_OWNER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.hasNext").value(false))
                .andReturn();

        JsonNode body = body(result);
        assertThat(body.path("data").isNull()).as("data は null ではなく空配列").isFalse();
        assertThat(body.path("data").size()).as("data は空配列").isZero();
        assertThat(body.path("meta").path("nextCursor").isNull() || body.path("meta").path("nextCursor").isMissingNode())
                .as("0 件では nextCursor を返さない")
                .isTrue();
    }

    @Test
    @DisplayName("AC49_sizeは1と100が成功・0と101は400")
    void AC49_size境界() throws Exception {
        for (String ok : List.of("1", "100")) {
            mockMvc.perform(get(INVOICES)
                            .with(user(String.valueOf(OWNER_ID)))
                            .param("scopeKind", "USER")
                            .param("scopeId", String.valueOf(OWNER_ID))
                            .param("size", ok))
                    .andExpect(status().isOk());
        }
        for (String ng : List.of("0", "101")) {
            mockMvc.perform(get(INVOICES)
                            .with(user(String.valueOf(OWNER_ID)))
                            .param("scopeKind", "USER")
                            .param("scopeId", String.valueOf(OWNER_ID))
                            .param("size", ng))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════ cursor ページング ═════════

    @Test
    @DisplayName("AC50_cursorは不透明base64・period_end同値とnullでも重複欠落なし")
    void AC50_cursorページングは重複も欠落もない() throws Exception {
        List<String> collected = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            MvcResult result = fetchPage(OWNER_ID, 2, cursor);
            JsonNode body = body(result);
            for (JsonNode item : body.path("data")) {
                collected.add(item.path("id").asText());
            }
            JsonNode next = body.path("meta").path("nextCursor");
            if (!body.path("meta").path("hasNext").asBoolean()) {
                assertThat(next.isNull() || next.isMissingNode())
                        .as("hasNext=false のとき nextCursor は返さない")
                        .isTrue();
                break;
            }
            cursor = next.asText();
            final String opaque = cursor;
            assertThatCode(() -> decodeBase64(opaque))
                    .as("cursor は base64 で包まれた不透明値であること（実測=%s）", opaque)
                    .doesNotThrowAnyException();
        }

        assertThat(collected)
                .as("全 %d 件がちょうど1回ずつ返る（重複・欠落なし）", ownerInvoiceIds.size())
                .hasSize(ownerInvoiceIds.size())
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        ownerInvoiceIds.stream().map(UUID::toString).toList());

        // period_end が null の行も含めて順序が決まっていること（2 回目の走査が同じ順序を返す）。
        List<String> second = new ArrayList<>();
        String c2 = null;
        for (int page = 0; page < 10; page++) {
            MvcResult result = fetchPage(OWNER_ID, 2, c2);
            JsonNode body = body(result);
            for (JsonNode item : body.path("data")) {
                second.add(item.path("id").asText());
            }
            if (!body.path("meta").path("hasNext").asBoolean()) {
                break;
            }
            c2 = body.path("meta").path("nextCursor").asText();
        }
        assertThat(second).as("null period_end を含めて順序が決定的であること").isEqualTo(collected);
    }

    @Test
    @DisplayName("AC51_ページング中に新invoiceが入っても既出行は再出現しない")
    void AC51_ページング中の追加でも再出現しない() throws Exception {
        MvcResult first = fetchPage(OWNER_ID, 2, null);
        JsonNode firstBody = body(first);
        List<String> firstPage = new ArrayList<>();
        for (JsonNode item : firstBody.path("data")) {
            firstPage.add(item.path("id").asText());
        }
        assertThat(firstBody.path("meta").path("hasNext").asBoolean()).isTrue();
        String cursor = firstBody.path("meta").path("nextCursor").asText();

        // ページングの途中で最新の invoice が到着する（period_end は既存のどれよりも新しい）。
        invoiceRepository.save(BillingInvoiceApiTestFixtures.invoice(
                EntitlementScopeKind.USER,
                OWNER_ID,
                UUID.randomUUID(),
                "in_arrived_" + UUID.randomUUID(),
                Instant.parse("2026-09-30T15:00:00Z"),
                9_000L));

        List<String> rest = new ArrayList<>();
        for (int page = 0; page < 10; page++) {
            MvcResult result = fetchPage(OWNER_ID, 2, cursor);
            JsonNode body = body(result);
            for (JsonNode item : body.path("data")) {
                rest.add(item.path("id").asText());
            }
            if (!body.path("meta").path("hasNext").asBoolean()) {
                break;
            }
            cursor = body.path("meta").path("nextCursor").asText();
        }

        assertThat(rest)
                .as("既に返した行が後続ページに再出現してはならない")
                .doesNotContainAnyElementsOf(firstPage);
        assertThat(rest).doesNotHaveDuplicates();
    }

    // ═════════ 明細 ═════════

    @Test
    @DisplayName("AC54_明細はlines・adjustments・subtotal/discount/total・税内訳を返す")
    void AC54_明細は内訳を返す() throws Exception {
        UUID invoiceId = ownerInvoiceIds.get(0);
        lineRepository.save(BillingInvoiceApiTestFixtures.line(invoiceId, "il_1", 1_000L));
        lineRepository.save(BillingInvoiceApiTestFixtures.line(invoiceId, "il_2", 2_000L));
        adjustmentRepository.save(BillingInvoiceApiTestFixtures.adjustment(invoiceId, "re_1", 500L));

        mockMvc.perform(get(INVOICES + "/{id}", invoiceId).with(user(String.valueOf(OWNER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(invoiceId.toString()))
                .andExpect(jsonPath("$.data.lines").isArray())
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].description").exists())
                .andExpect(jsonPath("$.data.lines[0].quantity").exists())
                .andExpect(jsonPath("$.data.lines[0].amountExcludingTax").exists())
                .andExpect(jsonPath("$.data.lines[0].amountIncludingTax").exists())
                // 税内訳（TaxBreakdown[]）は line ごとに返す。
                .andExpect(jsonPath("$.data.lines[0].taxes").isArray())
                .andExpect(jsonPath("$.data.lines[0].taxes[0].taxAmount").exists())
                .andExpect(jsonPath("$.data.lines[0].taxes[0].taxRateBasisPoints").exists())
                .andExpect(jsonPath("$.data.adjustments").isArray())
                .andExpect(jsonPath("$.data.adjustments.length()").value(1))
                .andExpect(jsonPath("$.data.adjustments[0].kind").value("REFUND"))
                .andExpect(jsonPath("$.data.subtotal.currency").value("JPY"))
                .andExpect(jsonPath("$.data.discount.currency").value("JPY"))
                .andExpect(jsonPath("$.data.total.currency").value("JPY"))
                .andExpect(jsonPath("$.data.issuer.name").exists());
    }

    // ═════════ 監査 ═════════

    @Test
    @DisplayName("AC60_BILLING_INVOICE_VIEWEDを記録しURL・住所全文・payloadを含めない")
    void AC60_監査は記録されPIIを含めない() throws Exception {
        UUID invoiceId = ownerInvoiceIds.get(0);
        mockMvc.perform(get(INVOICES + "/{id}", invoiceId).with(user(String.valueOf(OWNER_ID))))
                .andExpect(status().isOk());

        List<String> metadata = jdbcTemplate.queryForList(
                "SELECT COALESCE(metadata, '') FROM audit_logs "
                        + "WHERE event_type = ? AND user_id = ?",
                String.class,
                "BILLING_INVOICE_VIEWED",
                OWNER_ID);

        assertThat(metadata)
                .as("明細閲覧で BILLING_INVOICE_VIEWED を1件記録する")
                .hasSize(1);
        String json = metadata.get(0);
        assertThat(json).as("URL を監査に含めない").doesNotContain("http://").doesNotContain("https://");
        assertThat(json).as("住所全文を監査に含めない").doesNotContain("千代田区1-1-1");
        assertThat(json).as("payload を監査に含めない").doesNotContain("payload");
        assertThat(json).as("監査は object ref を残す").contains(invoiceId.toString());
    }

    // ═════════ helper ═════════

    private MvcResult fetchPage(long actorId, int size, String cursor) throws Exception {
        var request = get(INVOICES)
                .with(user(String.valueOf(actorId)))
                .param("scopeKind", "USER")
                .param("scopeId", String.valueOf(actorId))
                .param("size", String.valueOf(size));
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    /** URL-safe / 標準どちらの base64 でも復号できることだけを確かめる（中身は契約しない）。 */
    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getUrlDecoder().decode(value.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Base64.getDecoder().decode(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
