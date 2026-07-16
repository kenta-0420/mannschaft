package com.mannschaft.app.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB1 — payment（決済ドメイン）組織スコープ
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: 家老偵察により {@code OrganizationPaymentController} の 8 EP（一覧・手動記録・修正・
 * 一括記録・取消・リマインド・CSV エクスポート・<b>Stripe 実返金</b>）に認可が一切敷設されておらず、
 * 未認証以外は誰でも到達できていた。加えて {@code itemId} が path 上位スコープ（{@code organizationId}）に
 * 属するかを検証していなかったため、正当な自組織 ADMIN であっても他組織の {@code itemId} を渡せば
 * その支払い項目の入金記録を操作・返金できる BOLA（Broken Object Level Authorization）が成立していた。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。Stripe 境界は
 * {@code @MockitoBean(types = StripePaymentProvider.class)} で決定的にスタブし、実 Stripe API は叩かない。</p>
 *
 * <p><b>象限</b>: 非メンバー/非 ADMIN メンバー（outsider・memberOrgA）/ 別 scope ADMIN（BOLA①: orgB の ADMIN が
 * orgA の URL を叩く越境）/ 同一組織 ADMIN だが itemId が他組織所属（BOLA②: {@code checkAdminOrAbove} は通るが
 * {@code itemId} のスコープ検証で 404）/ 正当 ADMIN。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@MockitoBean(types = {StripePaymentProvider.class})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("payment（決済）ドメイン 組織スコープ 認可契約テスト（試練・Wave3-B1）")
class PaymentScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentItemRepository paymentItemRepository;

    @Autowired
    private MemberPaymentRepository memberPaymentRepository;

    @Autowired
    private StripePaymentProvider stripePaymentProvider;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;

    private Long adminOrgAId;   // ORG A の ADMIN（正当）
    private Long adminOrgBId;   // ORG B の ADMIN（別 scope の越境攻撃者）
    private Long memberOrgAId;  // ORG A の非 ADMIN メンバー
    private Long outsiderId;    // どこにも所属しない非メンバー
    private Long payableMemberOrgAId; // ORG A の非 ADMIN メンバー（未入金・手動記録の新規作成テスト専用）

    private Long itemOrgAId;    // 組織 A の支払い項目
    private Long itemOrgBId;    // 組織 B の支払い項目（BOLA②: itemId 越境検証用）

    private Long manualPaymentId; // 組織A・手動記録（CASH・PAID）
    private Long stripePaymentId; // 組織A・Stripe決済（PAID・返金対象）

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("PAYAUTHZ 組織A");
        orgBId = insertOrganization("PAYAUTHZ 組織B");

        adminOrgAId = insertUser("payauthz-admin-org-a@example.com");
        adminOrgBId = insertUser("payauthz-admin-org-b@example.com");
        memberOrgAId = insertUser("payauthz-member-org-a@example.com");
        outsiderId = insertUser("payauthz-outsider@example.com");
        payableMemberOrgAId = insertUser("payauthz-payable-member-org-a@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（EquipmentScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, payableMemberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        itemOrgAId = paymentItemRepository.save(PaymentItemEntity.builder()
                .organizationId(orgAId).name("PAYAUTHZ 年会費A").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("3000.00")).currency("JPY")
                .build()).getId();

        itemOrgBId = paymentItemRepository.save(PaymentItemEntity.builder()
                .organizationId(orgBId).name("PAYAUTHZ 年会費B").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("3000.00")).currency("JPY")
                .build()).getId();

        manualPaymentId = memberPaymentRepository.save(MemberPaymentEntity.builder()
                .userId(memberOrgAId).paymentItemId(itemOrgAId)
                .amountPaid(new BigDecimal("3000.00")).currency("JPY")
                .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.PAID)
                .paidAt(LocalDateTime.now())
                .build()).getId();

        stripePaymentId = memberPaymentRepository.save(MemberPaymentEntity.builder()
                .userId(memberOrgAId).paymentItemId(itemOrgAId)
                .amountPaid(new BigDecimal("3000.00")).currency("JPY")
                .paymentMethod(PaymentMethod.STRIPE).status(PaymentStatus.PAID)
                .stripePaymentIntentId("pi_payauthz_test")
                .paidAt(LocalDateTime.now())
                .build()).getId();

        // Stripe 実返金は叩かず、決定的スタブ値を返す（境界の外は本テストの検証対象外）。
        given(stripePaymentProvider.createRefund(anyString(), anyLong(), anyLong()))
                .willReturn("re_payauthz_test");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET .../payments（一覧・閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET .../payments（一覧）")
    class ListPayments {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他組織所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST .../payments（手動記録・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST .../payments（手動記録）")
    class CreateManualPayment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(manualBody(memberOrgAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(manualBody(memberOrgAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他組織所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(manualBody(memberOrgAId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminOrgAId);
            // memberOrgAId は @BeforeEach で itemOrgAId に対する PAID 記録（manualPaymentId）を
            // 既に保有しているため、同じ userId で新規作成すると ALREADY_PAID（PAYMENT_004・400）に
            // 正当に弾かれてしまう（PATCH/DELETE/refund 系テストの前提データを壊さず流用するための
            // フィクスチャ設計上の衝突）。未入金の payableMemberOrgAId を対象にして純粋な「新規作成」を検証する。
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(manualBody(payableMemberOrgAId))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> manualBody(Long userId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", userId);
            body.put("amountPaid", 3000);
            body.put("paidAt", LocalDateTime.now().toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PATCH .../payments/{paymentId}（修正・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PATCH .../payments/{paymentId}（修正）")
    class UpdatePayment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}",
                            orgAId, itemOrgAId, manualPaymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}",
                            orgAId, itemOrgAId, manualPaymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他組織所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}",
                            orgAId, itemOrgBId, manualPaymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}",
                            orgAId, itemOrgAId, manualPaymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("note", "PAYAUTHZ 修正済み");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST .../payments/bulk（一括記録・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST .../payments/bulk（一括記録）")
    class CreateBulkPayments {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments/bulk", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments/bulk", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他組織所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments/bulk", orgAId, itemOrgBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments/bulk", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> bulkBody() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userId", memberOrgAId);
            entry.put("amountPaid", 3000);
            entry.put("paidAt", LocalDateTime.now().toString());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("payments", List.of(entry));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. DELETE .../payments/{paymentId}（取消・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE .../payments/{paymentId}（取消）")
    class CancelPayment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}",
                            orgAId, itemOrgAId, manualPaymentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}",
                            orgAId, itemOrgAId, manualPaymentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他組織所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}",
                            orgAId, itemOrgBId, manualPaymentId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}",
                            orgAId, itemOrgAId, manualPaymentId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. POST .../remind（未払いリマインド・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST .../remind（リマインド送信）")
    class SendRemind {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/remind", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/remind", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他組織所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/remind", orgAId, itemOrgBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/remind", orgAId, itemOrgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET .../payments/export（CSVエクスポート・変更系相当: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET .../payments/export（CSVエクスポート）")
    class ExportPayments {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments/export", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments/export", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他組織所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments/export", orgAId, itemOrgBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items/{itemId}/payments/export", orgAId, itemOrgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. POST .../payments/{paymentId}/refund（Stripe実返金・最重要: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. POST .../payments/{paymentId}/refund（Stripe実返金）")
    class RefundPayment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}/refund",
                            orgAId, itemOrgAId, stripePaymentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}/refund",
                            orgAId, itemOrgAId, stripePaymentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他組織所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}/refund",
                            orgAId, itemOrgBId, stripePaymentId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200・Stripe実返金APIが呼ばれDBがREFUNDEDになる")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items/{itemId}/payments/{pid}/refund",
                            orgAId, itemOrgAId, stripePaymentId))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verify(stripePaymentProvider)
                    .createRefund("pi_payauthz_test", stripePaymentId, adminOrgAId);
            MemberPaymentEntity refunded = memberPaymentRepository.findById(stripePaymentId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            org.assertj.core.api.Assertions.assertThat(refunded.getStripeRefundId()).isEqualTo("re_payauthz_test");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'PAYAUTHZ', 'テスト', 'PAYAUTHZ テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
