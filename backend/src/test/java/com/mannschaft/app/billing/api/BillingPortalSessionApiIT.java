package com.mannschaft.app.billing.api;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR5 Billing Center: Stripe Customer Portal セッション発行 API（{@code POST /api/v1/me/billing/portal-sessions}）の
 * <b>HTTP 境界で観測できる</b>受け入れ条件（AC-61 / AC-62 / AC-63 / AC-69）を先行して固定する試練テスト。
 *
 * <p>正本: {@code docs/features/F20.1_entitlement_billing/05_billing_center.md}（:336 / :350 / :370）。</p>
 *
 * <p><b>実フィルタ鎖を通す理由</b>: PR4 で「controller 直叩きの単体テストは緑なのに SecurityConfig の
 * URL ルール層が全 callback を 401 にしていた」事故が起きている（{@link BillingReturnCallbackSecurityIT} 参照）。
 * 本 API も 401/403 の分岐そのものが受け入れ条件であるため {@code addFilters=false} にしない。</p>
 *
 * <p><b>Stripe を呼ばない検体だけを置いている</b>: ここに書いた 4 本はいずれも
 * 「Stripe へ出る前に必ず打ち切られる」経路である（未認証・他 scope・Customer 非 ACTIVE・冪等キー欠落）。
 * 成功経路（201 と Portal URL 返却）や rate limit の 11 回目（429）は、実装が入ると Stripe への
 * 実 API 呼び出しを誘発するため本 IT には置かず、{@link BillingPortalSessionContractTrialTest} の
 * 構造検証と、出陣時に足軽が用意する Stripe gateway のスタブ付きテストへ委ねる。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR5 Portal セッション発行 API の HTTP 境界 AC（試練）")
class BillingPortalSessionApiIT extends AbstractMySqlIntegrationTest {

    /** 正本 §336 が定める唯一の入口。 */
    private static final String PATH = "/api/v1/me/billing/portal-sessions";

    /** Portal を開こうとする actor。USER scope は actorId == scopeId のときだけ許可される。 */
    private static final long ACTOR_ID = 760_001L;

    /** actor が管理権限を持たない他人の USER scope。 */
    private static final long OTHER_SCOPE_ID = 760_002L;

    /** Customer が ACTIVE 以外（MIGRATION_REQUIRED）である scope。actor 本人ではある。 */
    private static final long INACTIVE_SCOPE_ID = ACTOR_ID;

    private static final String KEY = "00000000-0000-0000-0000-000000000761";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCustomers() {
        jdbcTemplate.update("DELETE FROM billing_customers WHERE scope_id IN (?, ?)",
                ACTOR_ID, OTHER_SCOPE_ID);
        // 本人 scope の Customer は「ACTIVE 以外」（AC-63 の検体）。
        insertCustomer(INACTIVE_SCOPE_ID, "MIGRATION_REQUIRED");
        // 他人 scope は ACTIVE。ここが 403 になるのは Customer の状態ではなく認可の結果であることを示す。
        insertCustomer(OTHER_SCOPE_ID, "ACTIVE");
    }

    private void insertCustomer(long scopeId, String status) {
        String pspRef = "ACTIVE".equals(status) ? "cus_trial_" + scopeId : null;
        jdbcTemplate.update("""
                INSERT INTO billing_customers
                    (id, scope_kind, scope_id, psp_customer_ref, status, provision_attempts, version,
                     created_at, updated_at)
                VALUES (?, 'USER', ?, ?, ?, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, toBytes(UUID.randomUUID()), scopeId, pspRef, status);
    }

    private static byte[] toBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    private static String body(long scopeId) {
        return "{\"scopeKind\":\"USER\",\"scopeId\":" + scopeId + "}";
    }

    @Test
    @DisplayName("AC62_未認証の Portal セッション発行は 401")
    void AC62_未認証は401() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ACTOR_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC61_他 scope への Portal セッション発行は BillingAccessGuard により 403")
    void AC61_他scopeは403() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(user(String.valueOf(ACTOR_ID)))
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(OTHER_SCOPE_ID)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC63_Customer が ACTIVE 以外なら Portal を開始せず 409")
    void AC63_ACTIVE以外は409() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(user(String.valueOf(ACTOR_ID)))
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(INACTIVE_SCOPE_ID)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AC69_Idempotency-Key ヘッダ欠落は 400")
    void AC69_冪等キー欠落は400() throws Exception {
        mockMvc.perform(post(PATH)
                        .with(user(String.valueOf(ACTOR_ID)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(ACTOR_ID)))
                .andExpect(status().isBadRequest());
    }
}
