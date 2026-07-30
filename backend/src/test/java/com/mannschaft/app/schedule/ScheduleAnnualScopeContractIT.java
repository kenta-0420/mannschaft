package com.mannschaft.app.schedule;

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
 * 認可根治戦役 Wave7 — schedule ドメインの年間行事（Annual Schedule）API 契約テスト。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} Wave7 節。
 * 金型は同ドメインの {@code ScheduleStatsAndInvitationScopeContractIT}（{@code @AutoConfigureMockMvc
 * (addFilters=false)} ＋実 MySQL ＋手動 SecurityContext ＋ {@code MembershipTestHelper}）をそのまま踏襲する。</p>
 *
 * <p>対象は {@link com.mannschaft.app.schedule.controller.OrgAnnualScheduleController} /
 * {@link com.mannschaft.app.schedule.controller.TeamAnnualScheduleController} の参照系 3 EP × 2 系統
 * （org/team）計 6 EP:</p>
 * <ul>
 *   <li><b>年間行事ビュー（getAnnualView）</b>・<b>コピープレビュー（previewCopy）</b> —
 *       既存スケジュールのタイトル・日時のみを扱う参照 API のため、当該スコープのメンバーのみ
 *       閲覧可（{@code checkMembership} 水準）。</li>
 *   <li><b>コピーログ一覧（getCopyLogs）</b> — 「誰がいつコピーを実行したか」という運用管理情報のため、
 *       実行系の {@code executeCopy} と同じ ADMIN/DEPUTY_ADMIN 限定（{@code checkAdminOrAbove} 水準）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("schedule 年間行事 認可契約テスト")
class ScheduleAnnualScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long adminOrgAId;    // ORG A の ADMIN（正当）
    private Long adminOrgBId;    // ORG B の ADMIN（別 scope）
    private Long memberOrgAId;   // ORG A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        teamAId = insertTeam("W7Annual チームA", "w7annual-team-a-" + nano);
        teamBId = insertTeam("W7Annual チームB", "w7annual-team-b-" + nano);
        orgAId = insertOrganization("W7Annual 組織A", "w7annual-org-a-" + nano);
        orgBId = insertOrganization("W7Annual 組織B", "w7annual-org-b-" + nano);

        adminTeamAId = insertUser("w7annual-admin-team-a-" + nano + "@example.com");
        adminTeamBId = insertUser("w7annual-admin-team-b-" + nano + "@example.com");
        memberTeamAId = insertUser("w7annual-member-team-a-" + nano + "@example.com");
        adminOrgAId = insertUser("w7annual-admin-org-a-" + nano + "@example.com");
        adminOrgBId = insertUser("w7annual-admin-org-b-" + nano + "@example.com");
        memberOrgAId = insertUser("w7annual-member-org-a-" + nano + "@example.com");
        outsiderId = insertUser("w7annual-outsider-" + nano + "@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（ScheduleStatsAndInvitationScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/schedules/annual（チーム年間行事ビュー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/schedules/annual")
    class TeamAnnualView {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(annualViewGet("/api/v1/teams/{teamId}/schedules/annual", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(annualViewGet("/api/v1/teams/{teamId}/schedules/annual", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（正常系・参照はメンバー水準）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(annualViewGet("/api/v1/teams/{teamId}/schedules/annual", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当なチームADMINは200（正常系）")
        void 正当なチームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(annualViewGet("/api/v1/teams/{teamId}/schedules/annual", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /organizations/{orgId}/schedules/annual（組織年間行事ビュー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /organizations/{orgId}/schedules/annual")
    class OrgAnnualView {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(annualViewGet("/api/v1/organizations/{orgId}/schedules/annual", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(annualViewGet("/api/v1/organizations/{orgId}/schedules/annual", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（正常系・参照はメンバー水準）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(annualViewGet("/api/v1/organizations/{orgId}/schedules/annual", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当な組織ADMINは200（正常系）")
        void 正当な組織ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(annualViewGet("/api/v1/organizations/{orgId}/schedules/annual", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /teams/{teamId}/schedules/annual/preview-copy（チームコピープレビュー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /teams/{teamId}/schedules/annual/preview-copy")
    class TeamPreviewCopy {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(previewCopyGet("/api/v1/teams/{teamId}/schedules/annual/preview-copy", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(previewCopyGet("/api/v1/teams/{teamId}/schedules/annual/preview-copy", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（正常系・プレビューはメンバー水準）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(previewCopyGet("/api/v1/teams/{teamId}/schedules/annual/preview-copy", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当なチームADMINは200（正常系）")
        void 正当なチームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(previewCopyGet("/api/v1/teams/{teamId}/schedules/annual/preview-copy", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. GET /organizations/{orgId}/schedules/annual/preview-copy（組織コピープレビュー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET /organizations/{orgId}/schedules/annual/preview-copy")
    class OrgPreviewCopy {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(previewCopyGet("/api/v1/organizations/{orgId}/schedules/annual/preview-copy", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(previewCopyGet("/api/v1/organizations/{orgId}/schedules/annual/preview-copy", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（正常系・プレビューはメンバー水準）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(previewCopyGet("/api/v1/organizations/{orgId}/schedules/annual/preview-copy", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当な組織ADMINは200（正常系）")
        void 正当な組織ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(previewCopyGet("/api/v1/organizations/{orgId}/schedules/annual/preview-copy", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET /teams/{teamId}/schedules/annual/copy-logs（チームコピーログ一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET /teams/{teamId}/schedules/annual/copy-logs")
    class TeamCopyLogs {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedules/annual/copy-logs", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（コピーログは運用管理情報のためADMIN水準）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedules/annual/copy-logs", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedules/annual/copy-logs", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当なチームADMINは200（正常系）")
        void 正当なチームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedules/annual/copy-logs", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET /organizations/{orgId}/schedules/annual/copy-logs（組織コピーログ一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET /organizations/{orgId}/schedules/annual/copy-logs")
    class OrgCopyLogs {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/schedules/annual/copy-logs", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（コピーログは運用管理情報のためADMIN水準）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/schedules/annual/copy-logs", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/schedules/annual/copy-logs", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当な組織ADMINは200（正常系）")
        void 正当な組織ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/schedules/annual/copy-logs", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 年間行事ビュー GET を必須クエリ（academic_year）付きで組み立てる。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder annualViewGet(
            String urlTemplate, Long scopeId) {
        return get(urlTemplate, scopeId).param("academic_year", "2026");
    }

    /** コピープレビュー GET を必須クエリ（source_year/target_year）付きで組み立てる。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder previewCopyGet(
            String urlTemplate, Long scopeId) {
        return get(urlTemplate, scopeId)
                .param("source_year", "2025")
                .param("target_year", "2026");
    }

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
                                + "VALUES (:email, 'W7Annual', 'テスト', 'W7Annual テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
