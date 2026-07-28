package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B6 — schedule ドメイン書込（{@code OrgScheduleController}/
 * {@code TeamScheduleController}/{@code ScheduleService}/{@code ScheduleAttendanceService}）
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} Wave3-B6 節。
 * update/delete/cancel/duplicate（BOLA）・出欠読取（getAttendances/exportAttendancesCsv）・
 * 出欠一括更新（bulkUpdateAttendances）が全て認可ゼロだった欠陥を是正する。</p>
 *
 * <p>金型: {@code TimetableScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。
 * {@code ScheduleService} の update/delete/cancel/duplicate は path の teamPublicId/orgPublicId を
 * 一切参照せず scheduleId のみで操作するため、「別 scope ADMIN が scheduleId を直接指定して
 * 越境する」BOLA を重点的に検証する。duplicateSchedule 自体は
 * {@code ScheduleCrossRefService#acceptInvitation}（招待受諾の正当系）と共有のため、認可は
 * public な複製 API 入口（{@code checkScopeAdminAccess}）で検証する。</p>
 *
 * <p><b>PERSONAL scope の扱い</b>: {@code ScheduleService} の write メソッドは TEAM/ORG 用
 * コントローラーからしか呼ばれない実装だが、{@code checkScopeAdminAccess}/{@code checkScopeViewAccess}
 * は entity 由来 scope で判定するため PERSONAL エンティティが混入しても 500 にならず
 * 所有者一致判定へ正しくフォールバックすることを直接検証する
 * （project_scopetype_cross_domain_personal_mismatch 回避の裏取り）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("schedule ドメイン書込・出欠 認可契約テスト（試練）")
class ScheduleWriteScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;
    private String teamASlug;
    private String teamBSlug;
    private String orgASlug;
    private String orgBSlug;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー
    private Long adminOrgAId;    // ORG A の ADMIN（正当）
    private Long adminOrgBId;    // ORG B の ADMIN（別 scope の越境攻撃者）
    private Long memberOrgAId;   // ORG A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー
    private Long personalOwnerId; // PERSONAL スケジュールの所有者（BOLA混同検証用）

    private Long teamScheduleAId;
    private Long orgScheduleAId;
    private Long personalScheduleId;

    @BeforeEach
    void setUp() {
        teamASlug = "wb6-team-a-" + System.nanoTime();
        teamBSlug = "wb6-team-b-" + System.nanoTime();
        orgASlug = "wb6-org-a-" + System.nanoTime();
        orgBSlug = "wb6-org-b-" + System.nanoTime();

        teamAId = insertTeam("WAVE3B6 チームA", teamASlug);
        teamBId = insertTeam("WAVE3B6 チームB", teamBSlug);
        orgAId = insertOrganization("WAVE3B6 組織A", orgASlug);
        orgBId = insertOrganization("WAVE3B6 組織B", orgBSlug);

        adminTeamAId = insertUser("wb6-admin-team-a@example.com");
        adminTeamBId = insertUser("wb6-admin-team-b@example.com");
        memberTeamAId = insertUser("wb6-member-team-a@example.com");
        adminOrgAId = insertUser("wb6-admin-org-a@example.com");
        adminOrgBId = insertUser("wb6-admin-org-b@example.com");
        memberOrgAId = insertUser("wb6-member-org-a@example.com");
        outsiderId = insertUser("wb6-outsider@example.com");
        personalOwnerId = insertUser("wb6-personal-owner@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（RepairPlanAuthorizationMatrixTest 踏襲）。
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
        // outsiderId / personalOwnerId はどこにも所属させない。

        ScheduleEntity teamScheduleA = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamAId)
                .title("WAVE3B6 チームA練習")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                // bulkUpdateAttendances の validateAttendanceRequired 通過のため true 必須
                .attendanceRequired(true)
                .createdBy(adminTeamAId)
                .build());
        teamScheduleAId = teamScheduleA.getId();

        ScheduleEntity orgScheduleA = scheduleRepository.save(ScheduleEntity.builder()
                .organizationId(orgAId)
                .title("WAVE3B6 組織Aイベント")
                .startAt(LocalDateTime.of(2026, 4, 2, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 2, 12, 0))
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(adminOrgAId)
                .build());
        orgScheduleAId = orgScheduleA.getId();

        ScheduleEntity personalSchedule = scheduleRepository.save(ScheduleEntity.builder()
                .userId(personalOwnerId)
                .title("WAVE3B6 個人予定")
                .startAt(LocalDateTime.of(2026, 4, 3, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 3, 12, 0))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(personalOwnerId)
                .build());
        personalScheduleId = personalSchedule.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 0. POST /teams|organizations/{publicId}/schedules（作成）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("0. POST /teams|organizations/{publicId}/schedules（作成）")
    class CreateSchedule {

        @Test
        @DisplayName("チーム作成: 非ADMINメンバーは403")
        void チーム作成_非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTeamBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム作成: 別scope ADMIN（teamBのADMIN）は403")
        void チーム作成_別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTeamBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム作成: 正当ADMINは201")
        void チーム作成_正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTeamBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("組織作成: 非ADMINメンバーは403")
        void 組織作成_非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgPublicId}/schedules", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織作成: 別scope ADMIN（orgBのADMIN）は403")
        void 組織作成_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgPublicId}/schedules", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織作成: 正当ADMINは201")
        void 組織作成_正当ADMINは201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgPublicId}/schedules", orgASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createTeamBody() {
            return Map.of(
                    "title", "新規チーム予定",
                    "startAt", "2026-05-01T10:00:00+09:00",
                    "allDay", false,
                    "eventType", "PRACTICE",
                    "attendanceRequired", false);
        }

        private Map<String, Object> createOrgBody() {
            return Map.of(
                    "title", "新規組織イベント",
                    "startAt", "2026-05-01T10:00:00+09:00",
                    "allDay", false,
                    "eventType", "EVENT",
                    "attendanceRequired", false);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. PATCH /teams/{teamPublicId}/schedules/{id}（更新）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. PATCH /teams/{teamPublicId}/schedules/{id}（更新）")
    class TeamUpdateSchedule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{id}", teamASlug, teamScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがscheduleIdを直接指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{id}", teamBSlug, teamScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{id}", teamASlug, teamScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("BOLA是正の裏取り: PERSONALスケジュールをTEAM書込EP経由で叩いても500にならず所有者以外は403")
        void PERSONALスケジュール混入は500でなく403() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{id}", teamASlug, personalScheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA是正の裏取り: PERSONALスケジュールは所有者本人なら200（TEAM URL経由でも500にならず正しく認可）")
        void PERSONALスケジュールは所有者本人なら200() throws Exception {
            setAuth(personalOwnerId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{id}", teamASlug, personalScheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. DELETE /teams/{teamPublicId}/schedules/{id}（削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. DELETE /teams/{teamPublicId}/schedules/{id}（削除）")
    class TeamDeleteSchedule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamPublicId}/schedules/{id}", teamASlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamPublicId}/schedules/{id}", teamBSlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamPublicId}/schedules/{id}", teamASlug, teamScheduleAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /teams/{teamPublicId}/schedules/{id}/cancel（キャンセル）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /teams/{teamPublicId}/schedules/{id}/cancel（キャンセル）")
    class TeamCancelSchedule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules/{id}/cancel", teamASlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules/{id}/cancel", teamBSlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules/{id}/cancel", teamASlug, teamScheduleAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /teams/{teamPublicId}/schedules/{id}/duplicate（複製・BOLA: 複製元(source) scope由来）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST /teams/{teamPublicId}/schedules/{id}/duplicate（複製）")
    class TeamDuplicateSchedule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules/{id}/duplicate", teamASlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINが複製元(source)のscheduleIdを直接指定）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules/{id}/duplicate", teamBSlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules/{id}/duplicate", teamASlug, teamScheduleAId))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET /teams/{teamPublicId}/schedules/{id}/attendances（出欠一覧・checkMembership水準）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET /teams/{teamPublicId}/schedules/{id}/attendances（出欠一覧）")
    class TeamGetAttendances {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances", teamASlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope（teamBのADMIN）は403（BOLA）")
        void 別scopeは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances", teamBSlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200（閲覧はcheckMembership水準）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances", teamASlug, teamScheduleAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances", teamASlug, teamScheduleAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET /teams/{teamPublicId}/schedules/{id}/attendances/export（出欠CSV）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET /teams/{teamPublicId}/schedules/{id}/attendances/export（出欠CSV）")
    class TeamExportAttendancesCsv {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances/export",
                            teamASlug, teamScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances/export",
                            teamASlug, teamScheduleAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PATCH /teams/{teamPublicId}/schedules/{id}/attendances/bulk（出欠一括更新・管理者用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. PATCH /teams/{teamPublicId}/schedules/{id}/attendances/bulk（出欠一括更新）")
    class TeamBulkUpdateAttendances {

        @Test
        @DisplayName("非ADMINメンバーは403（管理者用EPのため閲覧可でも書込不可）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances/bulk",
                            teamASlug, teamScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances/bulk",
                            teamBSlug, teamScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{id}/attendances/bulk",
                            teamASlug, teamScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isNoContent());
        }

        private Map<String, Object> bulkBody() {
            return Map.of("attendances", List.of(
                    Map.of("userId", memberTeamAId, "status", "ATTENDING")));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. 組織スケジュール: PATCH/DELETE/cancel/duplicate/attendances
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. 組織スケジュール（Org: 更新/削除/キャンセル/複製/出欠）")
    class OrgScheduleEndpoints {

        @Test
        @DisplayName("更新: 非ADMINメンバーは403")
        void 更新_非ADMINメンバーは403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{orgPublicId}/schedules/{id}", orgASlug, orgScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: 別scope ADMIN（orgBのADMIN）は403（BOLA）")
        void 更新_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(patch("/api/v1/organizations/{orgPublicId}/schedules/{id}", orgBSlug, orgScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: 正当ADMINは200")
        void 更新_正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{orgPublicId}/schedules/{id}", orgASlug, orgScheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "更新後"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("削除: 別scope ADMINは403（BOLA）")
        void 削除_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgPublicId}/schedules/{id}", orgBSlug, orgScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: 正当ADMINは204")
        void 削除_正当ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgPublicId}/schedules/{id}", orgASlug, orgScheduleAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("キャンセル: 別scope ADMINは403（BOLA）")
        void キャンセル_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgPublicId}/schedules/{id}/cancel", orgBSlug, orgScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("キャンセル: 正当ADMINは204")
        void キャンセル_正当ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgPublicId}/schedules/{id}/cancel", orgASlug, orgScheduleAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("複製: 別scope ADMIN（orgBのADMINが複製元(source)のscheduleIdを直接指定）は403（BOLA）")
        void 複製_別scopeADMINは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgPublicId}/schedules/{id}/duplicate",
                            orgBSlug, orgScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("複製: 正当ADMINは201")
        void 複製_正当ADMINは201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgPublicId}/schedules/{id}/duplicate",
                            orgASlug, orgScheduleAId))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("出欠一覧: 非メンバーは403")
        void 出欠一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules/{id}/attendances",
                            orgASlug, orgScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("出欠一覧: 別scope（orgBのADMIN）は403（BOLA）")
        void 出欠一覧_別scopeは403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules/{id}/attendances",
                            orgBSlug, orgScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("出欠一覧: 正当メンバーは200")
        void 出欠一覧_正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules/{id}/attendances",
                            orgASlug, orgScheduleAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("出欠CSV: 非メンバーは403")
        void 出欠CSV_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules/{id}/attendances/export",
                            orgASlug, orgScheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("出欠CSV: 正当メンバーは200")
        void 出欠CSV_正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules/{id}/attendances/export",
                            orgASlug, orgScheduleAId))
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
                                + "VALUES (:email, 'WAVE3B6', 'テスト', 'WAVE3B6 テスト', 'ACTIVE', "
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
