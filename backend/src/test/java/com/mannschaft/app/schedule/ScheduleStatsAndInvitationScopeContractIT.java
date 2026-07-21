package com.mannschaft.app.schedule;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleCrossRefEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCrossRefRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6-B5 — schedule ドメインの出席統計・招待受信系 API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} Wave6-B5 節。
 * 金型は同ドメインの {@code ScheduleWriteScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)}
 * ＋実 MySQL ＋手動 SecurityContext ＋ {@code MembershipTestHelper}）をそのまま踏襲する。
 * 秘匿ステータスも同ドメインの既存規約に合わせ 403 で固定する。</p>
 *
 * <p>対象は以下の 2 系統:</p>
 * <ul>
 *   <li><b>出席率統計</b>（{@code ScheduleCommonController}）— チーム/組織の取得・CSV エクスポート
 *       計 4 EP。名簿全体・期間横断のユーザー別出席率という管理者向け集計のため、当該スコープの
 *       ADMIN/DEPUTY_ADMIN のみ。<b>CSV は最大 1 万人分を返す経路のため重点的に固定する。</b></li>
 *   <li><b>スケジュール招待受信</b>（{@code ScheduleInvitationController}）— TEAM/ORGANIZATION 両系統の
 *       一覧/承認/拒否/最終確認 計 7 EP。一覧は受信側スコープのメンバー、状態遷移は受信側スコープの
 *       ADMIN。あわせて URL の scope と招待 entity の target の不一致（BOLA）を封鎖する。</li>
 * </ul>
 *
 * <p>日時フィクスチャは文字列リテラルではなく {@code LocalDateTime} で bind する
 * （JST/UTC 9 時間ズレで TZ 境界の集計が漏れる事故の回避）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("schedule 出席統計・招待受信 認可契約テスト（試練）")
class ScheduleStatsAndInvitationScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 統計 API の期間クエリ。LocalDateTime で組み立てて ISO 文字列化する（TZ ズレ回避）。 */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 4, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 4, 30, 23, 59);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleCrossRefRepository crossRefRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long adminOrgAId;    // ORG A の ADMIN（正当）
    private Long adminOrgBId;    // ORG B の ADMIN（別 scope の越境攻撃者）
    private Long memberOrgAId;   // ORG A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long teamInvitationId;  // TEAM A 宛の PENDING 招待
    private Long orgInvitationId;   // ORG A 宛の PENDING 招待

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        teamAId = insertTeam("W6B5 チームA", "w6b5-team-a-" + nano);
        teamBId = insertTeam("W6B5 チームB", "w6b5-team-b-" + nano);
        orgAId = insertOrganization("W6B5 組織A", "w6b5-org-a-" + nano);
        orgBId = insertOrganization("W6B5 組織B", "w6b5-org-b-" + nano);

        adminTeamAId = insertUser("w6b5-admin-team-a-" + nano + "@example.com");
        adminTeamBId = insertUser("w6b5-admin-team-b-" + nano + "@example.com");
        memberTeamAId = insertUser("w6b5-member-team-a-" + nano + "@example.com");
        adminOrgAId = insertUser("w6b5-admin-org-a-" + nano + "@example.com");
        adminOrgBId = insertUser("w6b5-admin-org-b-" + nano + "@example.com");
        memberOrgAId = insertUser("w6b5-member-org-a-" + nano + "@example.com");
        outsiderId = insertUser("w6b5-outsider-" + nano + "@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（ScheduleWriteScopeContractIT 踏襲）。
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

        // 招待元スケジュール（TEAM B 発 → TEAM A 宛 / ORG B 発 → ORG A 宛）。
        Long sourceTeamScheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamBId)
                .title("W6B5 招待元（チームB）")
                .startAt(LocalDateTime.of(2026, 4, 10, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 10, 12, 0))
                .eventType(EventType.MATCH)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(adminTeamBId)
                .build()).getId();

        Long sourceOrgScheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .organizationId(orgBId)
                .title("W6B5 招待元（組織B）")
                .startAt(LocalDateTime.of(2026, 4, 11, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 11, 12, 0))
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(adminOrgBId)
                .build()).getId();

        teamInvitationId = crossRefRepository.save(ScheduleCrossRefEntity.builder()
                .sourceScheduleId(sourceTeamScheduleId)
                .targetType(CrossRefTargetType.TEAM)
                .targetId(teamAId)
                .invitedBy(adminTeamBId)
                .status(CrossRefStatus.PENDING)
                .message("W6B5 チーム宛招待")
                .build()).getId();

        orgInvitationId = crossRefRepository.save(ScheduleCrossRefEntity.builder()
                .sourceScheduleId(sourceOrgScheduleId)
                .targetType(CrossRefTargetType.ORGANIZATION)
                .targetId(orgAId)
                .invitedBy(adminOrgBId)
                .status(CrossRefStatus.PENDING)
                .message("W6B5 組織宛招待")
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/attendance-stats（チーム出席率統計）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/attendance-stats")
    class TeamAttendanceStats {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(statsGet("/api/v1/teams/{teamId}/attendance-stats", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(statsGet("/api/v1/teams/{teamId}/attendance-stats", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(statsGet("/api/v1/teams/{teamId}/attendance-stats", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当なチームADMINは200（正常系）")
        void 正当なチームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(statsGet("/api/v1/teams/{teamId}/attendance-stats", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /teams/{teamId}/attendance-stats/export（チーム統計CSV）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /teams/{teamId}/attendance-stats/export（CSV）")
    class TeamAttendanceStatsExport {

        @Test
        @DisplayName("他チームの出席統計CSVは取得できない（非メンバー403）")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(statsGet("/api/v1/teams/{teamId}/attendance-stats/export", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームの出席統計CSVは取得できない（別scope ADMIN 403）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(statsGet("/api/v1/teams/{teamId}/attendance-stats/export", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(statsGet("/api/v1/teams/{teamId}/attendance-stats/export", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当なチームADMINは200（正常系）")
        void 正当なチームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(statsGet("/api/v1/teams/{teamId}/attendance-stats/export", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /organizations/{orgId}/attendance-stats（＋CSV）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /organizations/{orgId}/attendance-stats（＋CSV）")
    class OrgAttendanceStats {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(statsGet("/api/v1/organizations/{orgId}/attendance-stats", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(statsGet("/api/v1/organizations/{orgId}/attendance-stats", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(statsGet("/api/v1/organizations/{orgId}/attendance-stats", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当な組織ADMINは200（正常系）")
        void 正当な組織ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(statsGet("/api/v1/organizations/{orgId}/attendance-stats", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他組織の出席統計CSVは取得できない（別scope ADMIN 403）")
        void CSV_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(statsGet("/api/v1/organizations/{orgId}/attendance-stats/export", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("CSV: 非ADMINメンバーは403")
        void CSV_非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(statsGet("/api/v1/organizations/{orgId}/attendance-stats/export", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("CSV: 正当な組織ADMINは200（正常系）")
        void CSV_正当な組織ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(statsGet("/api/v1/organizations/{orgId}/attendance-stats/export", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. GET /teams|organizations/{id}/schedule-invitations（受信一覧）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET /{scope}/{id}/schedule-invitations（受信一覧）")
    class ListInvitations {

        @Test
        @DisplayName("チーム一覧: 非メンバーは403")
        void チーム一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedule-invitations", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム一覧: 別scope ADMIN（teamBのADMIN）は403")
        void チーム一覧_別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedule-invitations", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム一覧: 正当メンバーは200（正常系）")
        void チーム一覧_正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedule-invitations", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("組織一覧: 非メンバーは403")
        void 組織一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/schedule-invitations", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織一覧: 別scope ADMIN（orgBのADMIN）は403")
        void 組織一覧_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/schedule-invitations", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織一覧: 正当メンバーは200（正常系）")
        void 組織一覧_正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/schedule-invitations", orgAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST /{scope}/{id}/schedule-invitations/{invitationId}/{accept|reject|confirm}
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST /{scope}/{id}/schedule-invitations/{invitationId}/*（状態遷移）")
    class RespondInvitation {

        @Test
        @DisplayName("チーム承認: 非メンバーは403")
        void チーム承認_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/accept",
                            teamAId, teamInvitationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム承認: 非ADMINメンバーは403")
        void チーム承認_非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/accept",
                            teamAId, teamInvitationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム承認: 送信側（teamBのADMIN）は受信側の権限が無いので403")
        void チーム承認_送信側ADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/accept",
                            teamAId, teamInvitationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: 自分がADMINのteamBのURLで、teamA宛の招待IDを拒否できない")
        void BOLA_自scopeのURLで他scope宛招待は403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/reject",
                            teamBId, teamInvitationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: 組織入口からチーム宛の招待IDを操作できない")
        void BOLA_組織入口からチーム宛招待は403() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/schedule-invitations/{invitationId}/reject",
                            orgAId, teamInvitationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム拒否: 正当な受信側ADMINは204（正常系）")
        void チーム拒否_正当な受信側ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/reject",
                            teamAId, teamInvitationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("組織拒否: 非ADMINメンバーは403")
        void 組織拒否_非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/schedule-invitations/{invitationId}/reject",
                            orgAId, orgInvitationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織拒否: 別scope ADMIN（orgBのADMIN）は403")
        void 組織拒否_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/schedule-invitations/{invitationId}/reject",
                            orgAId, orgInvitationId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織拒否: 正当な受信側ADMINは204（正常系）")
        void 組織拒否_正当な受信側ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/schedule-invitations/{invitationId}/reject",
                            orgAId, orgInvitationId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("チーム最終確認: 非ADMINメンバーは403")
        void チーム最終確認_非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/confirm",
                            teamAId, teamInvitationId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 統計 API の GET を期間クエリ付きで組み立てる。 */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statsGet(
            String urlTemplate, Long scopeId) {
        return get(urlTemplate, scopeId)
                .param("from", FROM.toString())
                .param("to", TO.toString());
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
                                + "VALUES (:email, 'W6B5', 'テスト', 'W6B5 テスト', 'ACTIVE', "
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
