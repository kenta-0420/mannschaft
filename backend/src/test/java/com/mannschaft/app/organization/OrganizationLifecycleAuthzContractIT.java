package com.mannschaft.app.organization;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6 — organization ドメインのライフサイクル操作 4EP 認可契約テスト。
 *
 * <p>{@code OrganizationController} は {@code renameSlug} / {@code changeRole} / {@code removeMember} /
 * supporter 系では {@code AccessControlService.checkAdminOrAbove} を敷いていたが、
 * <b>削除・アーカイブ・アーカイブ解除・復元の 4EP だけが認可ガードなし</b>で素通しになっていた
 * （典型的な回収漏れ）。本テストがその番人となる。</p>
 *
 * <h3>粒度の設計根拠</h3>
 * <ul>
 *   <li><b>delete / archive / unarchive</b> = {@code checkAdminOrAbove}。
 *       同一クラスの兄弟 EP（{@code renameSlug} 等・組織そのものの設定変更）と同じ流儀に揃える。</li>
 *   <li><b>restore</b> = {@code checkSystemAdmin}。{@code OrganizationService#restoreOrganization} の Javadoc と
 *       Controller の {@code @Operation(summary = "組織復元（SYSTEM_ADMINのみ）")} が
 *       いずれも SYSTEM_ADMIN 専用と宣言しているため、{@code checkAdminOrAbove} では緩すぎる。
 *       <b>組織 ADMIN でも 403</b> になることを本テストで明示的に固定する。</li>
 * </ul>
 *
 * <h3>ガードを Controller 入口に敷く理由（Service 側に置かない）</h3>
 * <p>{@code archiveOrganization} / {@code unarchiveOrganization} は
 * {@code SystemAdminDashboardController}（{@code /api/v1/system-admin/**} = SecurityConfig:419 で
 * {@code hasRole("SYSTEM_ADMIN")}）の凍結/凍結解除 EP からも呼ばれる共有メソッドである。
 * Service 側に {@code checkAdminOrAbove} を置くと、対象組織のメンバーではない SYSTEM_ADMIN が
 * 管理コンソールから凍結できなくなり、正当な運用経路を巻き添えで壊す。
 * よって認可は各 public 入口（Controller）に敷く。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("organization ライフサイクル操作（削除/アーカイブ/復元）認可契約テスト（Wave6）")
class OrganizationLifecycleAuthzContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private String orgSlug;

    private Long adminId;      // 対象組織の ADMIN（正当）
    private Long memberId;     // 対象組織の非 ADMIN メンバー
    private Long outsiderId;   // どこにも所属しない非メンバー
    private Long systemAdminId; // プラットフォーム SYSTEM_ADMIN（対象組織には未所属）

    @BeforeEach
    void setUp() {
        Long orgId = insertOrganization("ORGAUTHZ 組織A");
        orgSlug = selectSlug(orgId);

        adminId = insertUser("orgauthz-admin@example.com");
        memberId = insertUser("orgauthz-member@example.com");
        outsiderId = insertUser("orgauthz-outsider@example.com");
        systemAdminId = insertUser("orgauthz-sysadmin@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーには両方の行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", null, orgId);

        // 一般メンバーは memberships のみ（user_roles を張らない＝ADMIN 判定は通らない）。
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);

        // SYSTEM_ADMIN はプラットフォームレベル割当（team_id / organization_id ともに NULL）。
        // AccessControlService#isSystemAdmin が引く existsSystemAdminByUserId の実クエリ条件に一致させる。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        // outsiderId はどこにも所属させない。

        em.flush();
        em.clear();
    }

    private String org() {
        return "/api/v1/organizations/" + orgSlug;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. DELETE /organizations/{slug}（組織削除・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. DELETE /organizations/{slug}（組織削除・checkAdminOrAbove）")
    class DeleteOrganization {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete(org())).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(delete(org())).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminId);
            mockMvc.perform(delete(org())).andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PATCH /organizations/{slug}/archive（アーカイブ・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PATCH /organizations/{slug}/archive（アーカイブ・checkAdminOrAbove）")
    class ArchiveOrganization {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch(org() + "/archive")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(patch(org() + "/archive")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminId);
            mockMvc.perform(patch(org() + "/archive")).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PATCH /organizations/{slug}/unarchive（アーカイブ解除・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PATCH /organizations/{slug}/unarchive（アーカイブ解除・checkAdminOrAbove）")
    class UnarchiveOrganization {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch(org() + "/unarchive")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(patch(org() + "/unarchive")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminId);
            mockMvc.perform(patch(org() + "/unarchive")).andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PATCH /organizations/{slug}/restore（復元・checkSystemAdmin）
    //    組織 ADMIN でも 403 になることが本 EP の肝。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PATCH /organizations/{slug}/restore（復元・checkSystemAdmin）")
    class RestoreOrganization {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch(org() + "/restore")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberId);
            mockMvc.perform(patch(org() + "/restore")).andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織ADMINでも403（SYSTEM_ADMIN専用のため checkAdminOrAbove では緩すぎる）")
        void 組織ADMINでも403() throws Exception {
            setAuth(adminId);
            mockMvc.perform(patch(org() + "/restore")).andExpect(status().isForbidden());
        }

        /**
         * SYSTEM_ADMIN はガードを通過する（403 にならない）。
         *
         * <p>対象組織が論理削除されていないため、ガード通過後に Service 本体が
         * {@code ORG_006}（削除されていないため復元できません）で弾くのが正しい挙動である
         * （ロットD: {@code ERROR_CODE_STATUS_MAP} 登録により 409 CONFLICT が期待値として固定できる）。</p>
         *
         * <p><b>既知の制約</b>: {@code restore} EP は現状 <b>本来の用途で到達不能</b>である。
         * Controller が呼ぶ {@code resolveOrgId} は {@code findBySlugAndDeletedAtIsNullAndLifecycleStatus}
         * （ACTIVE限定・検分P1-2根治）を引くため、論理削除済み組織の slug は解決できず ORG_001 になる。
         * すなわち「削除済み組織を slug で復元する」
         * 経路が成立しない。これは本タスク（認可）の範囲外の既存の機能バグであり、別途起票が必要。
         * そのため「正当 SYSTEM_ADMIN → 復元成功（204）」の正常系は本テストでは検証できない。</p>
         */
        @Test
        @DisplayName("SYSTEM_ADMINはガードを通過する（403にならない・ORG_006で409）")
        void SYSTEM_ADMINはガードを通過する() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(patch(org() + "/restore")).andExpect(status().isConflict());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. ロットDステータス契約（ORG_003）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. ロットDステータス契約（ARCHIVED状態競合）")
    class LotDStatusContract {

        @Test
        @DisplayName("既にアーカイブ済みの組織を再アーカイブすると409（ORG_003）")
        void 再アーカイブは409() throws Exception {
            setAuth(adminId);
            mockMvc.perform(patch(org() + "/archive")).andExpect(status().isOk());
            mockMvc.perform(patch(org() + "/archive")).andExpect(status().isConflict());
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
                                + "VALUES (:email, 'ORGAUTHZ', 'テスト', 'ORGAUTHZ テスト', 'ACTIVE', "
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

    private String selectSlug(Long orgId) {
        return (String) em.createNativeQuery("SELECT slug FROM organizations WHERE id = :id")
                .setParameter("id", orgId)
                .getSingleResult();
    }
}
