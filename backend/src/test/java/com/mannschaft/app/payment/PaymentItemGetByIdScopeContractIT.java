package com.mannschaft.app.payment;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #2657: {@code GET /api/v1/payment-items/{itemId}}（{@link com.mannschaft.app.payment.controller.PaymentCheckoutController#getPaymentItem}）
 * 認可契約テスト。
 *
 * <p>金型: {@code PaymentB1bScopeContractIT}（同一パッケージ）。{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}。</p>
 *
 * <p>観点: チーム所属項目/組織所属項目それぞれについて、非メンバーは403・当該スコープの
 * 非ADMINメンバーは200（閲覧系は {@code checkMembership} のため ADMIN 権限は不要）。
 * さらに TERM 型項目は {@code term} フィールドに有効期間が camelCase で返ることを確認する
 * （FE {@code usePaymentApi.getPaymentItemById} の契約）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@MockitoBean(types = {StripePaymentProvider.class})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PaymentCheckoutController#getPaymentItem 認可契約テスト（Issue #2657）")
class PaymentItemGetByIdScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentItemRepository paymentItemRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;

    private Long memberTeamAId;
    private Long outsiderId;

    private Long termItemInTeamAId;
    private Long itemInOrgAId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("PIGBI チームA");
        teamBId = insertTeam("PIGBI チームB");
        orgAId = insertOrganization("PIGBI 組織A");

        memberTeamAId = insertUser("pigbi-member-team-a@example.com");
        outsiderId = insertUser("pigbi-outsider@example.com");

        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        termItemInTeamAId = paymentItemRepository.save(PaymentItemEntity.builder()
                .teamId(teamAId).name("PIGBI 期別会費").type(PaymentItemType.TERM)
                .amount(new BigDecimal("3000.00")).currency("JPY")
                .termStartsOn(LocalDate.of(2026, 4, 1)).termEndsOn(LocalDate.of(2026, 9, 30))
                .build()).getId();

        itemInOrgAId = paymentItemRepository.save(PaymentItemEntity.builder()
                .organizationId(orgAId).name("PIGBI 年会費").type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("5000.00")).currency("JPY")
                .build()).getId();

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("チーム所属のTERM型項目")
    class TeamScopedTermItem {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/payment-items/{itemId}", termItemInTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームのメンバーは403（越境）")
        void 別チームメンバーは403() throws Exception {
            Long memberTeamBId = insertUser("pigbi-member-team-b@example.com");
            MembershipTestHelper.insertMembership(em, memberTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
            em.flush();
            em.clear();

            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/payment-items/{itemId}", termItemInTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバー（非ADMIN）は200・term フィールドに有効期間が camelCase で返る")
        void 正当メンバーは200_term付き() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/payment-items/{itemId}", termItemInTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.meta.type").value("TERM"))
                    .andExpect(jsonPath("$.data.term.termStartsOn").value("2026-04-01"))
                    .andExpect(jsonPath("$.data.term.termEndsOn").value("2026-09-30"));
        }
    }

    @Nested
    @DisplayName("組織所属の項目")
    class OrgScopedItem {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/payment-items/{itemId}", itemInOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織メンバー（非ADMIN）は200")
        void 組織メンバーは200() throws Exception {
            Long memberOrgAId = insertUser("pigbi-member-org-a@example.com");
            MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
            em.flush();
            em.clear();

            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/payment-items/{itemId}", itemInOrgAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.meta.type").value("ANNUAL_FEE"));
        }
    }

    @Test
    @DisplayName("存在しない itemId は404（PAYMENT_001）")
    void 存在しないitemIdは404() throws Exception {
        setAuth(memberTeamAId);
        mockMvc.perform(get("/api/v1/payment-items/{itemId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_001"));
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
                                + "VALUES (:email, 'PIGBI', 'テスト', 'PIGBI テスト', 'ACTIVE', "
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
                                + "CONCAT('t-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
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
