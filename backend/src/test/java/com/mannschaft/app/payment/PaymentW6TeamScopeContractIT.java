package com.mannschaft.app.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6（B3・payment）— チームスコープ課金 姉妹3コントローラ ＋ 手動入金記録
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>組織（ORGANIZATION）側の同名 API は Wave3-B1 / Wave5早馬B1b で
 * {@code AccessControlService} の敷設が完了している（{@code PaymentScopeContractIT} /
 * {@code PaymentB1bScopeContractIT}）。本 IT は<b>チーム（TEAM）側を組織側と同水準に揃える</b>ことを固定する。</p>
 *
 * <p>本 IT が固定する契約（10 EP）:</p>
 * <ul>
 *   <li>{@code TeamPaymentItemController} 4EP — 一覧は {@code checkMembership}、
 *       作成/更新/削除は {@code checkAdminOrAbove}（"TEAM"）</li>
 *   <li>{@code TeamAccessRequirementController} 2EP — GET は {@code checkMembership}、
 *       PUT は {@code checkAdminOrAbove}</li>
 *   <li>{@code TeamContentPaymentGateController} 2EP — GET は {@code checkMembership}、
 *       PUT は {@code checkAdminOrAbove}</li>
 *   <li>{@code TeamPaymentController} の手動入金記録（単一 / 一括）2EP — 双子の
 *       {@code OrganizationPaymentController} と同じく {@code checkAdminOrAbove} ＋
 *       {@code itemId} のチーム帰属検証（越境は 404・存在秘匿）</li>
 * </ul>
 *
 * <p>金型: {@code PaymentB1bScopeContractIT}（Wave5早馬B1b・同一パッケージ）。
 * {@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext +
 * {@code MembershipTestHelper}。</p>
 *
 * <p><b>象限</b>: 非メンバー（403）/ 非 ADMIN メンバー（変更系 403・閲覧系 200）/
 * 別 scope ADMIN＝越境 team id（403）/ 正当 ADMIN だが {@code itemId} が他チーム所属（404・BOLA）/
 * 正当 ADMIN（成功）。</p>
 *
 * <p>手動入金の正常系は受益者所属判定（AC-6）を通るため、{@code roles} の priority
 * （ADMIN(2) &lt; MEMBER(4) &lt; SUPPORTER(5)）を seed する
 * （test profile は Flyway 無効で {@code V2.014__seed_roles.sql} が流れないため）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@MockitoBean(types = {StripePaymentProvider.class})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("payment（決済）ドメイン チーム課金 10EP 認可契約テスト（試練・Wave6 B3）")
class PaymentW6TeamScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentItemRepository paymentItemRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;   // チームA の ADMIN（正当）
    private Long adminTeamBId;   // チームB の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // チームA の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long itemTeamAId;    // チーム A の支払い項目
    private Long itemTeamBId;    // チーム B の支払い項目（itemId 越境検証用）

    @BeforeEach
    void setUp() {
        // roles は priority 比較（受益者所属判定 AC-6）に効くため明示 seed する。
        ensureRole("ADMIN", 2);
        ensureRole("MEMBER", 4);
        ensureRole("SUPPORTER", 5);

        teamAId = insertTeam("PAYW6 チームA");
        teamBId = insertTeam("PAYW6 チームB");

        adminTeamAId = insertUser("payw6-admin-team-a@example.com");
        adminTeamBId = insertUser("payw6-admin-team-b@example.com");
        memberTeamAId = insertUser("payw6-member-team-a@example.com");
        outsiderId = insertUser("payw6-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（PaymentB1bScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        itemTeamAId = paymentItemRepository.save(PaymentItemEntity.builder()
                .teamId(teamAId).name("PAYW6 年会費A").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("3000.00")).currency("JPY")
                .build()).getId();

        itemTeamBId = paymentItemRepository.save(PaymentItemEntity.builder()
                .teamId(teamBId).name("PAYW6 年会費B").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("3000.00")).currency("JPY")
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. TeamPaymentItemController（4EP）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{id}/payment-items（一覧・checkMembership）")
    class ListPaymentItems {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-items", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-items", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-items", teamAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("2. POST /teams/{id}/payment-items（作成・checkAdminOrAbove）")
    class CreatePaymentItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "PAYW6 新規項目");
            body.put("type", "ITEM");
            body.put("amount", 1000);
            return body;
        }
    }

    @Nested
    @DisplayName("3. PATCH /teams/{id}/payment-items/{itemId}（更新・checkAdminOrAbove）— 金額改竄の遮断")
    class UpdatePaymentItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/payment-items/{itemId}", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）— 他チームの支払いアイテムの金額改竄を遮断")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{id}/payment-items/{itemId}", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/payment-items/{itemId}", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "PAYW6 更新済み");
            body.put("amount", 99999);
            return body;
        }
    }

    @Nested
    @DisplayName("4. DELETE /teams/{id}/payment-items/{itemId}（削除・checkAdminOrAbove）— 他チーム項目の削除を遮断")
    class DeletePaymentItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{id}/payment-items/{itemId}", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{id}/payment-items/{itemId}", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{id}/payment-items/{itemId}", teamAId, itemTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. TeamAccessRequirementController（2EP）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET /teams/{id}/access-requirements（取得・checkMembership）")
    class GetAccessRequirements {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/access-requirements", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{id}/access-requirements", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{id}/access-requirements", teamAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("6. PUT /teams/{id}/access-requirements（設定・checkAdminOrAbove）")
    class SetAccessRequirements {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{id}/access-requirements", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemIdsBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）— 空リスト送信でペイウォール要件全消去を狙う攻撃を遮断")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{id}/access-requirements", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("paymentItemIds", List.of()))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{id}/access-requirements", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemIdsBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> itemIdsBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("paymentItemIds", List.of(itemTeamAId));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. TeamContentPaymentGateController（2EP）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET /teams/{id}/content-payment-gates（一覧・checkMembership）")
    class ListContentGates {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/content-payment-gates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{id}/content-payment-gates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{id}/content-payment-gates", teamAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("8. PUT /teams/{id}/content-payment-gates（一括設定・checkAdminOrAbove）")
    class SetContentGates {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{id}/content-payment-gates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(gateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）— 無認可でのゲート全消去を遮断")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{id}/content-payment-gates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(gateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{id}/content-payment-gates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(gateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> gateBody() {
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("paymentItemId", itemTeamAId);
            gate.put("isTitleHidden", false);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contentType", "POST");
            body.put("contentId", 12345L);
            body.put("gates", List.of(gate));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. TeamPaymentController 手動入金記録（2EP・双子の Organization 側と同水準へ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. POST .../payment-items/{itemId}/payments（手動入金記録・checkAdminOrAbove）")
    class CreateManualPayment {

        @Test
        @DisplayName("非ADMINメンバーは403（自分を受益者にした自己入金記録も遮断）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(manualBody(memberTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(manualBody(memberTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他チーム所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(manualBody(memberTeamAId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは201（正常系）")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(manualBody(memberTeamAId))))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("10. POST .../payment-items/{itemId}/payments/bulk（一括入金記録・checkAdminOrAbove）")
    class CreateBulkPayments {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments/bulk", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments/bulk", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他チーム所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments/bulk", teamAId, itemTeamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200（正常系）")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments/bulk", teamAId, itemTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> bulkBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("payments", List.of(manualBody(memberTeamAId)));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 手動入金記録の JSON ボディ。{@code @Valid} はガードより先に走るため、403/404 を期待する
     * ケースでも必須項目（userId / amountPaid / paidAt）を必ず充足させる（bind 時 400 で
     * 認可に到達しない事故を防ぐ）。
     */
    private Map<String, Object> manualBody(Long beneficiaryUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", beneficiaryUserId);
        body.put("amountPaid", 3000);
        body.put("paidAt", LocalDateTime.now().format(ISO));
        body.put("paymentMethod", "CASH");
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * roles を priority 付きで冪等 seed する。test profile は Flyway 無効
     * （{@code ddl-auto=create}）のため {@code V2.014__seed_roles.sql} が流れず、
     * {@code MembershipTestHelper} のオンデマンド INSERT では priority=99 になってしまい
     * ロール優劣比較（MEMBER(4) &lt; SUPPORTER(5)）が成立しない。
     */
    private void ensureRole(String name, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.intValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :name, :pri, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("pri", priority)
                .executeUpdate();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'PAYW6', 'テスト', 'PAYW6 テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('payw6-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
