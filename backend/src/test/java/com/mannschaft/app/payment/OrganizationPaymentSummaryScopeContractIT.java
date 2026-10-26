package com.mannschaft.app.payment;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB1b — payment（決済ドメイン）支払いサマリー
 * {@code OrganizationPaymentSummaryController} / {@code TeamPaymentSummaryController} API 契約テスト
 * （試練 / red 先行）。
 *
 * <p>正本: 依頼文（Wave3-B1b payment節 追加確認④）。両コントローラーとも
 * {@code paymentSummaryService.get{Organization,Team}PaymentSummary} を呼ぶだけで認可が一切
 * 敷設されておらず、未認証以外は誰でも会費総額/未払い/期限切れ件数の集計を閲覧できていた
 * （双子構成のため同一 IT で両方を検証する）。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}（Wave3-B1・{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("payment（決済）ドメイン 支払いサマリー 認可契約テスト（試練・Wave3-B1b）")
class OrganizationPaymentSummaryScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long teamAId;
    private Long teamBId;

    private Long memberOrgAId;
    private Long outsiderOrgId;
    private Long memberTeamAId;
    private Long outsiderTeamId;

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("PSAUTHZ 組織A");
        orgBId = insertOrganization("PSAUTHZ 組織B");
        teamAId = insertTeam("PSAUTHZ チームA");
        teamBId = insertTeam("PSAUTHZ チームB");

        memberOrgAId = insertUser("psauthz-member-org-a@example.com");
        outsiderOrgId = insertUser("psauthz-outsider-org@example.com");
        memberTeamAId = insertUser("psauthz-member-team-a@example.com");
        outsiderTeamId = insertUser("psauthz-outsider-team@example.com");

        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderOrgId / outsiderTeamId はどちらのスコープにも一切所属しない。
        // orgBId/teamBId は「別scope越境」を明示するために作るが、本 IT では非会員403の検証のみで
        // 越境専用ケースは持たない（checkMembership は path scope のみを見るため BOLA の余地がない）。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /organizations/{id}/payment-summary（checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /organizations/{id}/payment-summary")
    class OrganizationSummary {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderOrgId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-summary", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別組織のメンバーは403（越境）")
        void 別組織のメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-summary", orgBId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/payment-summary", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /teams/{id}/payment-summary（checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /teams/{id}/payment-summary")
    class TeamSummary {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderTeamId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-summary", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームのメンバーは403（越境）")
        void 別チームのメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-summary", teamBId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{id}/payment-summary", teamAId))
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
                                + "VALUES (:email, 'PSAUTHZ', 'テスト', 'PSAUTHZ テスト', 'ACTIVE', "
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
                                + "CONCAT('ps-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('ps-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
