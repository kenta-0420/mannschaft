package com.mannschaft.app.membership;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.repository.MemberCardRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B9: membership ドメイン（会員証停止/再開・スコープ全体チェックイン履歴）
 * API 契約テスト（試練）。
 *
 * <p>正本: 早馬（殿からの直接指示・Wave3-B9依頼文）。{@code AccessControlService}
 * （{@code isMember}/{@code checkAdminOrAbove}）。金型: {@code SupporterScopeContractIT}。</p>
 *
 * <p>認可モデル:</p>
 * <ul>
 *   <li><b>suspend/reactivate</b>（{@code /api/v1/member-cards/{id}/...}。scope を宣言する
 *       query パラメータを持たない「ID 直指定」EP）: entity（会員証）由来 scope に非所属なら
 *       存在秘匿のため 404（MEMBERSHIP_001）、所属だが ADMIN でない場合は 403。</li>
 *   <li><b>getScopeCheckins</b>（{@code /api/v1/teams/{teamId}/checkins}。scope が path で
 *       明示的に宣言される EP）: checkAdminOrAbove で 403（COMMON_002）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("membership 会員証停止/再開・スコープチェックイン履歴 認可契約テスト（Wave3-B9）")
class MemberCardMutationScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberCardRepository memberCardRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    private MemberCardEntity activeCardA;    // teamA・ACTIVE（suspend成功対象）
    private MemberCardEntity suspendedCardA; // teamA・SUSPENDED（reactivate成功対象）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("MC認可契約チームA");
        teamBId = insertTeam("MC認可契約チームB");

        adminAId = insertUser("mc-authz-admin-a@example.com");
        adminBId = insertUser("mc-authz-admin-b@example.com");
        memberAId = insertUser("mc-authz-member-a@example.com");
        outsiderId = insertUser("mc-authz-outsider@example.com");

        // 注意: このクラスは com.mannschaft.app.membership パッケージに属するため、無修飾 ScopeType は
        // 同パッケージの com.mannschaft.app.membership.ScopeType（会員証entity用）に解決される。
        // MembershipTestHelper.insertMembership は F00.5系 com.mannschaft.app.membership.domain.ScopeType を
        // 要求するため、ここは完全修飾する（import追加はentity builderのScopeTypeと衝突するため不可）。
        MembershipTestHelper.insertMembership(em, adminAId, com.mannschaft.app.membership.domain.ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, com.mannschaft.app.membership.domain.ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, com.mannschaft.app.membership.domain.ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        activeCardA = memberCardRepository.save(MemberCardEntity.builder()
                .userId(memberAId).scopeType(ScopeType.TEAM).scopeId(teamAId)
                .cardCode("mc-authz-active-" + System.nanoTime()).cardNumber("TEAM-0001")
                .displayName("MC認可 会員A").status(CardStatus.ACTIVE)
                .issuedAt(LocalDateTime.now().minusDays(30)).checkinCount(0)
                .totalSpend(BigDecimal.ZERO).qrSecret("secret-active").build());

        suspendedCardA = memberCardRepository.save(MemberCardEntity.builder()
                .userId(memberAId).scopeType(ScopeType.TEAM).scopeId(teamAId)
                .cardCode("mc-authz-suspended-" + System.nanoTime()).cardNumber("TEAM-0002")
                .displayName("MC認可 会員A2").status(CardStatus.SUSPENDED)
                .issuedAt(LocalDateTime.now().minusDays(30)).suspendedAt(LocalDateTime.now().minusDays(1))
                .checkinCount(0).totalSpend(BigDecimal.ZERO).qrSecret("secret-suspended").build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. PATCH /api/v1/member-cards/{id}/suspend（ID直指定EP・isMember-conceal+checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 会員証停止(suspend)")
    class Suspend {

        @Test
        @DisplayName("非ADMINメンバーの停止は403")
        void 非ADMINの停止は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/member-cards/{id}/suspend", activeCardA.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非所属(越境)ADMINの停止は404（BOLA存在秘匿）")
        void 非所属の停止は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/member-cards/{id}/suspend", activeCardA.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_001"));
        }

        @Test
        @DisplayName("正当ADMINの停止は200")
        void 正当ADMINの停止は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/member-cards/{id}/suspend", activeCardA.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PATCH /api/v1/member-cards/{id}/reactivate（同上・suspendと対称）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 会員証再開(reactivate)")
    class Reactivate {

        @Test
        @DisplayName("非ADMINメンバーの再開は403")
        void 非ADMINの再開は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/member-cards/{id}/reactivate", suspendedCardA.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非所属(越境)ADMINの再開は404（BOLA存在秘匿）")
        void 非所属の再開は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/member-cards/{id}/reactivate", suspendedCardA.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_001"));
        }

        @Test
        @DisplayName("正当ADMINの再開は200")
        void 正当ADMINの再開は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/member-cards/{id}/reactivate", suspendedCardA.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /api/v1/teams/{teamId}/checkins（getScopeCheckins・scope宣言型）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. スコープ全体チェックイン履歴(getScopeCheckins)")
    class GetScopeCheckins {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkins", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkins", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMIN（越境）は403")
        void 他チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkins", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/checkins", teamAId))
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
                                + "VALUES (:email, 'MC契約', 'テスト', 'MC契約テスト', 'ACTIVE', "
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
                                + "CONCAT('mc-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
