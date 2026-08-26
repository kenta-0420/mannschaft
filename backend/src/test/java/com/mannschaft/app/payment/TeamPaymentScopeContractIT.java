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
 * 認可根治戦役 Wave 3 バッチB1b — payment（決済ドメイン）チームスコープ
 * {@code TeamPaymentController} API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: 依頼文（Wave3-B1b payment節）・双子の {@code OrganizationPaymentController}
 * （Wave3-B1 で既に同水準の認可を敷設済み・{@code PaymentScopeContractIT}）。
 * {@code TeamPaymentController} は listPayments/updatePayment/cancelPayment/<b>refundPayment（Stripe実返金）</b>
 * の 4EP に認可が一切敷設されておらず、未認証以外は誰でも到達できていた。加えて {@code itemId} が
 * path 上位スコープ（{@code teamId}）に属するかを検証していなかったため、正当な自チーム ADMIN であっても
 * 他チームの {@code itemId} を渡せばその支払い項目の入金記録を操作・返金できる BOLA が成立していた。</p>
 *
 * <p>createManualPayment/createBulkPayments は本 IT では検証しない
 * （Wave6 B3 で Organization 側と同水準の入口ガードを敷設し、{@code PaymentW6TeamScopeContractIT}
 * が契約を固定している）。sendRemind は既存の
 * {@code checkAdminOrAbove} に加えて本戦役で itemId のチーム帰属検証を追加したため、そのBOLA是正も検証する。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}（Wave3-B1・{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。Stripe 境界は
 * {@code @MockitoBean(types = StripePaymentProvider.class)} で決定的にスタブし、実 Stripe API は叩かない。</p>
 *
 * <p>member_payments/payment_items は API（createManualPayment 等）経由ではなく repository 直挿入で
 * seed する（AC-6 受益者所属判定 = {@code hasRoleOrAbove(..,"TEAM","MEMBER")} が roles.name="MEMBER" 行を
 * 要求する落とし穴を回避するため。listPayments/update/cancel/refund/remind はこの判定を経由しない）。</p>
 *
 * <p><b>象限</b>: 非メンバー/非ADMINメンバー（outsider・memberTeamA）/ 別 scope ADMIN（BOLA①: teamB の ADMIN が
 * teamA の URL を叩く越境）/ 同一チーム ADMIN だが itemId が他チーム所属（BOLA②: {@code checkAdminOrAbove} は通るが
 * {@code itemId} のスコープ検証で 404）/ 正当 ADMIN。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@MockitoBean(types = {StripePaymentProvider.class})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("payment（決済）ドメイン チームスコープ 認可契約テスト（試練・Wave3-B1b）")
class TeamPaymentScopeContractIT extends AbstractMySqlIntegrationTest {

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

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;   // チームA の ADMIN（正当）
    private Long adminTeamBId;   // チームB の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // チームA の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long itemTeamAId;    // チーム A の支払い項目
    private Long itemTeamBId;    // チーム B の支払い項目（BOLA②: itemId 越境検証用）

    private Long manualPaymentId; // チームA・手動記録（CASH・PAID）
    private Long stripePaymentId; // チームA・Stripe決済（PAID・返金対象）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("TMPAYAUTHZ チームA");
        teamBId = insertTeam("TMPAYAUTHZ チームB");

        adminTeamAId = insertUser("tmpayauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("tmpayauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("tmpayauthz-member-team-a@example.com");
        outsiderId = insertUser("tmpayauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（PaymentScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        itemTeamAId = paymentItemRepository.save(PaymentItemEntity.builder()
                .teamId(teamAId).name("TMPAYAUTHZ 年会費A").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("3000.00")).currency("JPY")
                .build()).getId();

        itemTeamBId = paymentItemRepository.save(PaymentItemEntity.builder()
                .teamId(teamBId).name("TMPAYAUTHZ 年会費B").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("3000.00")).currency("JPY")
                .build()).getId();

        // AC-6/authorizePayment を経由しない repository 直挿入（listPayments/update/cancel/remind/refund は
        // これらの判定を通らないため、MEMBER role 行の追加 seed は不要）。
        manualPaymentId = memberPaymentRepository.save(MemberPaymentEntity.builder()
                .userId(memberTeamAId).paymentItemId(itemTeamAId)
                .amountPaid(new BigDecimal("3000.00")).currency("JPY")
                .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.PAID)
                .paidAt(LocalDateTime.now())
                .build()).getId();

        stripePaymentId = memberPaymentRepository.save(MemberPaymentEntity.builder()
                .userId(memberTeamAId).paymentItemId(itemTeamAId)
                .amountPaid(new BigDecimal("3000.00")).currency("JPY")
                .paymentMethod(PaymentMethod.STRIPE).status(PaymentStatus.PAID)
                .stripePaymentIntentId("pi_tmpayauthz_test")
                .paidAt(LocalDateTime.now())
                .build()).getId();

        // Stripe 実返金は叩かず、決定的スタブ値を返す（境界の外は本テストの検証対象外）。
        given(stripePaymentProvider.createRefund(anyString(), anyLong(), anyLong()))
                .willReturn("re_tmpayauthz_test");

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
            mockMvc.perform(get("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他チーム所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-items/{itemId}/payments", teamAId, itemTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PATCH .../payments/{paymentId}（修正・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PATCH .../payments/{paymentId}（修正）")
    class UpdatePayment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}",
                            teamAId, itemTeamAId, manualPaymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}",
                            teamAId, itemTeamAId, manualPaymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他チーム所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}",
                            teamAId, itemTeamBId, manualPaymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}",
                            teamAId, itemTeamAId, manualPaymentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("note", "TMPAYAUTHZ 修正済み");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. DELETE .../payments/{paymentId}（取消・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. DELETE .../payments/{paymentId}（取消）")
    class CancelPayment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}",
                            teamAId, itemTeamAId, manualPaymentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}",
                            teamAId, itemTeamAId, manualPaymentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他チーム所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}",
                            teamAId, itemTeamBId, manualPaymentId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}",
                            teamAId, itemTeamAId, manualPaymentId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST .../payments/{paymentId}/refund（Stripe実返金・最重要: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST .../payments/{paymentId}/refund（Stripe実返金）")
    class RefundPayment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}/refund",
                            teamAId, itemTeamAId, stripePaymentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}/refund",
                            teamAId, itemTeamAId, stripePaymentId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがitemIdが他チーム所属は404（BOLA）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}/refund",
                            teamAId, itemTeamBId, stripePaymentId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200・Stripe実返金APIが呼ばれDBがREFUNDEDになる")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/payments/{pid}/refund",
                            teamAId, itemTeamAId, stripePaymentId))
                    .andExpect(status().isOk());

            org.mockito.Mockito.verify(stripePaymentProvider)
                    .createRefund("pi_tmpayauthz_test", stripePaymentId, adminTeamAId);
            MemberPaymentEntity refunded = memberPaymentRepository.findById(stripePaymentId).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            org.assertj.core.api.Assertions.assertThat(refunded.getStripeRefundId()).isEqualTo("re_tmpayauthz_test");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST .../remind（既存 checkAdminOrAbove の itemId 越境是正・Wave3-B1b で追加）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST .../remind（itemId 越境是正）")
    class SendRemindBolaFix {

        @Test
        @DisplayName("正当ADMINだがitemIdが他チーム所属は404（BOLA是正・Wave3-B1bで追加）")
        void itemId越境は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/remind", teamAId, itemTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMIN・itemIdもteamA配下なら200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{id}/payment-items/{itemId}/remind", teamAId, itemTeamAId))
                    .andExpect(status().isOk());
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
                                + "VALUES (:email, 'TMPAYAUTHZ', 'テスト', 'TMPAYAUTHZ テスト', 'ACTIVE', "
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
                                + "CONCAT('tmpay-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
