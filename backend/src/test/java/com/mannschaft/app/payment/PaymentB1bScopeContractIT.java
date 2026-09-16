package com.mannschaft.app.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.cms.repository.BlogPostRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave5早馬（B1b）— payment（決済ドメイン）組織課金 姉妹3コントローラ
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: 兄弟 {@code OrganizationPaymentController} は Wave3-B1 で全 EP に
 * {@code AccessControlService} を敷設済みだったが、同じ {@code /api/v1/organizations/{id}/...}
 * 配下の姉妹3コントローラだけ未注入で素通りしていた:</p>
 * <ul>
 *   <li>{@code OrganizationAccessRequirementController.setAccessRequirements} — 空
 *       {@code paymentItemIds} 送信で組織のペイウォール要件を全消去（無料開放）できる欠陥</li>
 *   <li>{@code OrganizationPaymentItemController.deletePaymentItem} 等（書込全般） — 他組織の
 *       支払い項目を操作・削除できる欠陥（{@code itemId} 自体の組織帰属は既存の
 *       {@code findByIdAndOrganizationId} で守られているが、org 認可がゼロだった）</li>
 *   <li>{@code OrganizationContentPaymentGateController.setContentGates} — 組織認可無しで
 *       コンテンツゲートを全消去できる欠陥</li>
 * </ul>
 *
 * <p>金型: {@code PaymentScopeContractIT}（Wave3-B1・同一パッケージ）。
 * {@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext +
 * {@code MembershipTestHelper}。</p>
 *
 * <p><b>象限</b>（3コントローラ代表EPで検証）: 非メンバー（403）/ 別scope ADMIN＝越境org id（403）/
 * 正当org ADMIN（成功）。閲覧系（GET）は非ADMINメンバーも成功する（{@code checkMembership}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@MockitoBean(types = {StripePaymentProvider.class})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("payment（決済）ドメイン 組織課金 姉妹3EP 認可契約テスト（試練・Wave5早馬B1b）")
class PaymentB1bScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentItemRepository paymentItemRepository;

    @MockitoBean
    private BlogPostRepository blogPostRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;

    private Long adminOrgAId;   // ORG A の ADMIN（正当）
    private Long adminOrgBId;   // ORG B の ADMIN（別 scope の越境攻撃者）
    private Long memberOrgAId;  // ORG A の非 ADMIN メンバー
    private Long outsiderId;    // どこにも所属しない非メンバー

    private Long itemOrgAId;    // 組織 A の支払い項目（アクセス要件/コンテンツゲート設定の対象）

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("PAYB1B 組織A");
        orgBId = insertOrganization("PAYB1B 組織B");

        // P0-A の実在コンテンツ検証を通すため、契約テストで使う固定 ID を orgA に所属させる。
        when(blogPostRepository.existsByIdAndOrganizationId(12345L, orgAId)).thenReturn(true);

        adminOrgAId = insertUser("payb1b-admin-org-a@example.com");
        adminOrgBId = insertUser("payb1b-admin-org-b@example.com");
        memberOrgAId = insertUser("payb1b-member-org-a@example.com");
        outsiderId = insertUser("payb1b-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（PaymentScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        itemOrgAId = paymentItemRepository.save(PaymentItemEntity.builder()
                .organizationId(orgAId).name("PAYB1B 年会費A").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("3000.00")).currency("JPY")
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. OrganizationAccessRequirementController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET .../access-requirements（アクセス要件取得・checkMembership）")
    class GetAccessRequirements {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/access-requirements", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/access-requirements", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/access-requirements", orgAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("2. PUT .../access-requirements（アクセス要件設定・checkAdminOrAbove）")
    class SetAccessRequirements {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/access-requirements", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemIdsBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403（越境）— 空リスト送信でペイウォール要件全消去を狙う攻撃を遮断")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(put("/api/v1/organizations/{id}/access-requirements", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("paymentItemIds", List.of()))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/access-requirements", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemIdsBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> itemIdsBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("paymentItemIds", List.of(itemOrgAId));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. OrganizationPaymentItemController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET .../payment-items（一覧・checkMembership）")
    class ListPaymentItems {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-items", orgAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("4. POST .../payment-items（作成・checkAdminOrAbove）")
    class CreatePaymentItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/payment-items", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "PAYB1B 新規項目");
            body.put("type", "ITEM");
            body.put("amount", 1000);
            return body;
        }
    }

    @Nested
    @DisplayName("5. PATCH .../payment-items/{itemId}（更新・checkAdminOrAbove）")
    class UpdatePaymentItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/payment-items/{itemId}", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/payment-items/{itemId}", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/payment-items/{itemId}", orgAId, itemOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "PAYB1B 更新済み");
            return body;
        }
    }

    @Nested
    @DisplayName("6. DELETE .../payment-items/{itemId}（削除・checkAdminOrAbove）— 他組織支払い項目の無認可削除を遮断")
    class DeletePaymentItem {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/payment-items/{itemId}", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/payment-items/{itemId}", orgAId, itemOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/payment-items/{itemId}", orgAId, itemOrgAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. OrganizationContentPaymentGateController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET .../content-payment-gates（一覧・checkMembership）")
    class ListContentGates {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/content-payment-gates", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/content-payment-gates", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/content-payment-gates", orgAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("8. PUT .../content-payment-gates（一括設定・checkAdminOrAbove）— 無認可でのゲート全消去を遮断")
    class SetContentGates {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/content-payment-gates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(gateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(put("/api/v1/organizations/{id}/content-payment-gates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(gateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/content-payment-gates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(gateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> gateBody() {
            Map<String, Object> gate = new LinkedHashMap<>();
            gate.put("paymentItemId", itemOrgAId);
            gate.put("isTitleHidden", false);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contentType", "POST");
            body.put("contentId", 12345L);
            body.put("gates", List.of(gate));
            return body;
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
                                + "VALUES (:email, 'PAYB1B', 'テスト', 'PAYB1B テスト', 'ACTIVE', "
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
