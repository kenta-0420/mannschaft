package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.1 Billing Center PR5 — 課金履歴 API の <b>認可・存在秘匿</b>受け入れテスト（試練・red）。
 *
 * <p>対象 AC: AC-44 / AC-45 / AC-46 / AC-47 / AC-52 / AC-53 / AC-55 / AC-56。
 * 正本: {@code docs/features/F20.1_entitlement_billing/05_billing_center.md} §7・§9(BC-02/BC-12/BC-20)。</p>
 *
 * <p><b>実装は未着手</b>である。本 IT は URL を文字列で叩き HTTP ステータス・エラーコード・
 * JSON 構造だけを観測するため、コンパイルは通り「実行されて期待と違う」形で赤くなる。</p>
 *
 * <p><b>フィルタを外さない理由</b>: 401/403 の分岐は Spring Security の URL ルール層と
 * メソッド認可の両方に跨がる。{@code addFilters=false} にすると AC-44（未認証 401）を
 * 原理的に観測できない（{@code BillingReturnCallbackSecurityIT} と同じ方針）。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F20.1 課金履歴 API 認可・存在秘匿（試練 red）")
class BillingInvoiceApiAuthorizationRedIT extends AbstractMySqlIntegrationTest {

    private static final String INVOICES = "/api/v1/me/billing/invoices";
    private static final String SCOPES = "/api/v1/me/billing/scopes";

    /** 検体の所有者（USER scope は actorId == scopeId のみ許可される）。 */
    private static final long OWNER_ID = 700_101L;
    /** 他人。OWNER の invoice へは到達できてはならない。 */
    private static final long STRANGER_ID = 700_102L;
    /** 一般 MEMBER 相当（TEAM の ADMIN 行も permission group も持たない）。 */
    private static final long MEMBER_ID = 700_103L;
    /** SYSTEM_ADMIN 権限文字列だけを持つ actor。 */
    private static final long SYSTEM_ADMIN_ID = 700_104L;

    private static final long TEAM_ID = 700_901L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BillingInvoiceJpaRepository invoiceRepository;

    @Autowired
    private BillingAccessGuard billingAccessGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID ownerInvoiceId;

    @BeforeEach
    void setUp() {
        invoiceRepository.deleteAll();
        BillingInvoiceEntity owned = invoiceRepository.save(BillingInvoiceApiTestFixtures.invoice(
                EntitlementScopeKind.USER,
                OWNER_ID,
                UUID.randomUUID(),
                "in_owner_" + UUID.randomUUID(),
                Instant.parse("2026-07-31T15:00:00Z"),
                11_000L));
        ownerInvoiceId = owned.getId();
    }

    // ═════════ 一覧の認可 ═════════

    @Test
    @DisplayName("AC44_未認証の一覧は401")
    void AC44_未認証の一覧は401() throws Exception {
        mockMvc.perform(get(INVOICES)
                        .param("scopeKind", "USER")
                        .param("scopeId", String.valueOf(OWNER_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC45_他scopeの一覧要求は403（管理scopeでない）")
    void AC45_他scopeの一覧要求は403() throws Exception {
        mockMvc.perform(get(INVOICES)
                        .with(user(String.valueOf(STRANGER_ID)))
                        .param("scopeKind", "USER")
                        .param("scopeId", String.valueOf(OWNER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_005"));
    }

    @Test
    @DisplayName("AC46_一般MEMBERはinvoiceをreadできず403")
    void AC46_一般MEMBERは403() throws Exception {
        mockMvc.perform(get(INVOICES)
                        .with(user(String.valueOf(MEMBER_ID)).roles("MEMBER"))
                        .param("scopeKind", "TEAM")
                        .param("scopeId", String.valueOf(TEAM_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_005"));
    }

    @Test
    @DisplayName("AC47_SYSTEM_ADMIN権限だけでは消費者APIに通らず403")
    void AC47_SYSTEM_ADMIN権限だけでは403() throws Exception {
        mockMvc.perform(get(INVOICES)
                        .with(user(String.valueOf(SYSTEM_ADMIN_ID)).roles("SYSTEM_ADMIN"))
                        .param("scopeKind", "TEAM")
                        .param("scopeId", String.valueOf(TEAM_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_005"));

        // 明細側でも短絡許可しない（他 scope の id は存在秘匿の 404）。
        mockMvc.perform(get(INVOICES + "/{id}", ownerInvoiceId)
                        .with(user(String.valueOf(SYSTEM_ADMIN_ID)).roles("SYSTEM_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_018"));
    }

    // ═════════ 明細の存在秘匿 ═════════

    @Test
    @DisplayName("AC52_他scopeのinvoice idは403ではなく404（存在秘匿）")
    void AC52_他scopeのidは404() throws Exception {
        MvcResult result = mockMvc.perform(get(INVOICES + "/{id}", ownerInvoiceId)
                        .with(user(String.valueOf(STRANGER_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_018"))
                .andReturn();

        // 存在オラクルを残さないため、本文は「存在しない id」の応答と同一でなければならない。
        MvcResult absent = mockMvc.perform(get(INVOICES + "/{id}", UUID.randomUUID())
                        .with(user(String.valueOf(STRANGER_ID))))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(errorCode(result)).isEqualTo(errorCode(absent));
    }

    @Test
    @DisplayName("AC53_存在しないidは404でENTITLEMENT_018")
    void AC53_存在しないidは404() throws Exception {
        mockMvc.perform(get(INVOICES + "/{id}", UUID.randomUUID())
                        .with(user(String.valueOf(OWNER_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ENTITLEMENT_018"));
    }

    // ═════════ scope 列挙 ═════════

    @Test
    @DisplayName("AC55_scopes は課金を管理できるscopeだけを返す")
    void AC55_scopesは管理可能scopeだけを返す() throws Exception {
        MvcResult result = mockMvc.perform(get(SCOPES).with(user(String.valueOf(OWNER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andReturn();

        JsonNode items = body(result).path("data").path("items");
        assertThat(items.size()).as("items が空でないこと").isPositive();
        // 本人の USER scope は必ず含まれ、manage=true である。
        boolean hasOwnUserScope = false;
        for (JsonNode item : items) {
            if ("USER".equals(item.path("kind").asText()) && item.path("id").asLong() == OWNER_ID) {
                hasOwnUserScope = true;
                assertThat(item.path("manage").asBoolean())
                        .as("本人の USER scope は manage=true")
                        .isTrue();
            }
        }
        assertThat(hasOwnUserScope).as("本人の USER scope が列挙されること").isTrue();
    }

    @Test
    @DisplayName("AC56_scopes に権限のないscopeが混ざらない（IDOR）")
    void AC56_scopesに権限のないscopeが混ざらない() throws Exception {
        MvcResult result = mockMvc.perform(get(SCOPES).with(user(String.valueOf(OWNER_ID))))
                .andExpect(status().isOk())
                .andReturn();

        for (JsonNode item : body(result).path("data").path("items")) {
            EntitlementScopeKind kind = EntitlementScopeKind.valueOf(item.path("kind").asText());
            long scopeId = item.path("id").asLong();
            // 返した scope すべてが、実 Guard で許可される scope であること（列挙が認可の唯一の真実源にならない）。
            assertThat(billingAccessGuard.canManageByActorId(OWNER_ID, kind, scopeId))
                    .as("列挙された scope %s:%d は BillingAccessGuard が許可しなければならない", kind, scopeId)
                    .isTrue();
            // 他人の USER scope は原理的に混ざってはならない。
            if (kind == EntitlementScopeKind.USER) {
                assertThat(scopeId).isEqualTo(OWNER_ID);
            }
        }
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String errorCode(MvcResult result) throws Exception {
        return body(result).path("error").path("code").asText();
    }
}
