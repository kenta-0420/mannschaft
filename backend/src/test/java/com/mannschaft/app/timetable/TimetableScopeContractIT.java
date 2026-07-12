package com.mannschaft.app.timetable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetablePeriodTemplateEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetablePeriodTemplateRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 2 トランシェ2B — timetable ドメイン（時間割・学期・スロット・臨時変更）
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} トランシェ2B timetable 節。
 * timetable ドメインは {@code AccessControlService} が一切敷設されておらず、読取だけでなく
 * 作成・更新・削除・複製まで任意チーム/組織に対して可能な状態だった。</p>
 *
 * <p>金型: {@code ServiceRecordScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（BOLA: teamB・orgB の ADMIN が
 * teamA・orgA へアクセス）/ 非 ADMIN メンバー / 正当 ADMIN。
 * {@code TimetableSlotController}/{@code TimetableChangeController}/{@code TimetableTermCommonController}
 * は path に teamId/orgId が無く timetableId・termId のみを受け取るため、
 * 「別 scope ADMIN が対象 ID を直接指定して越境する」BOLA を重点的に検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("timetable ドメイン（時間割）認可契約テスト（試練）")
class TimetableScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TimetableRepository timetableRepository;

    @Autowired
    private TimetableTermRepository termRepository;

    @Autowired
    private TimetableSlotRepository slotRepository;

    @Autowired
    private TimetableChangeRepository changeRepository;

    @Autowired
    private TimetablePeriodTemplateRepository periodTemplateRepository;

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

    private Long termAId;        // TEAM A の学期
    private Long termOrgAId;     // ORG A の学期（組織スコープ）
    private Long draftTimetableAId;   // TEAM A の時間割（DRAFT）
    private Long activeTimetableAId;  // TEAM A の時間割（ACTIVE・臨時変更用）
    private Long changeAId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("TTAUTHZ チームA");
        teamBId = insertTeam("TTAUTHZ チームB");
        orgAId = insertOrganization("TTAUTHZ 組織A");
        orgBId = insertOrganization("TTAUTHZ 組織B");
        insertTeamOrgMembership(teamAId, orgAId);
        insertTeamOrgMembership(teamBId, orgBId);

        adminTeamAId = insertUser("ttauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("ttauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("ttauthz-member-team-a@example.com");
        adminOrgAId = insertUser("ttauthz-admin-org-a@example.com");
        adminOrgBId = insertUser("ttauthz-admin-org-b@example.com");
        memberOrgAId = insertUser("ttauthz-member-org-a@example.com");
        outsiderId = insertUser("ttauthz-outsider@example.com");

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
        // outsiderId はどこにも所属させない。

        TimetableTermEntity termA = termRepository.save(TimetableTermEntity.builder()
                .teamId(teamAId).academicYear(2025).name("TTAUTHZ 1学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(1).build());
        termAId = termA.getId();

        TimetableTermEntity termOrgA = termRepository.save(TimetableTermEntity.builder()
                .organizationId(orgAId).academicYear(2025).name("TTAUTHZ 組織学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(1).build());
        termOrgAId = termOrgA.getId();

        TimetableEntity draftTimetableA = timetableRepository.save(TimetableEntity.builder()
                .teamId(teamAId).termId(termAId).name("TTAUTHZ 時間割（下書き）")
                .status(TimetableStatus.DRAFT).visibility(TimetableVisibility.MEMBERS_ONLY)
                .effectiveFrom(LocalDate.of(2025, 4, 1)).effectiveUntil(LocalDate.of(2025, 9, 30))
                .weekPatternEnabled(false).build());
        draftTimetableAId = draftTimetableA.getId();

        TimetableEntity activeTimetableA = timetableRepository.save(TimetableEntity.builder()
                .teamId(teamAId).termId(termAId).name("TTAUTHZ 時間割（有効）")
                .status(TimetableStatus.ACTIVE).visibility(TimetableVisibility.MEMBERS_ONLY)
                .effectiveFrom(LocalDate.of(2025, 4, 1)).effectiveUntil(LocalDate.of(2025, 9, 30))
                .weekPatternEnabled(false).build());
        activeTimetableAId = activeTimetableA.getId();

        slotRepository.save(TimetableSlotEntity.builder()
                .timetableId(draftTimetableAId).dayOfWeek("MON").periodNumber(1)
                .weekPattern(WeekPattern.EVERY).subjectName("数学").build());

        TimetableChangeEntity changeA = changeRepository.save(TimetableChangeEntity.builder()
                .timetableId(activeTimetableAId).targetDate(LocalDate.now().plusDays(1))
                .periodNumber(1).changeType(TimetableChangeType.REPLACE)
                .subjectName("体育").notifyMembers(false).build());
        changeAId = changeA.getId();

        periodTemplateRepository.save(TimetablePeriodTemplateEntity.builder()
                .organizationId(orgAId).periodNumber(1).label("1時限")
                .startTime(LocalTime.of(8, 45)).endTime(LocalTime.of(9, 35)).isBreak(false).build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/timetables（一覧・閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/timetables（一覧）")
    class ListTimetables {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetables", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetables", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetables", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetables", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /teams/{teamId}/timetables（作成・変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/timetables（作成）")
    class CreateTimetable {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/timetables", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/timetables", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/timetables", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "新規時間割");
            body.put("termId", termAId);
            body.put("effectiveFrom", LocalDate.of(2025, 4, 1).toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /teams/{teamId}/timetables/{id}（詳細・entity由来: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /teams/{teamId}/timetables/{id}（詳細）")
    class GetTimetable {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetables/{id}", teamAId, draftTimetableAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAのURLを叩く越境）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetables/{id}", teamAId, draftTimetableAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetables/{id}", teamAId, draftTimetableAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PATCH/DELETE /teams/{teamId}/timetables/{id}（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PATCH/DELETE /teams/{teamId}/timetables/{id}")
    class UpdateDeleteTimetable {

        @Test
        @DisplayName("非ADMINメンバーは更新403")
        void 非ADMINメンバーは更新403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/timetables/{id}", teamAId, draftTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは更新403（BOLA）")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/timetables/{id}", teamAId, draftTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/timetables/{id}", teamAId, draftTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "更新後"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別scope ADMINは削除403（BOLA）")
        void 別scopeADMINは削除403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/timetables/{id}", teamAId, draftTimetableAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/timetables/{id}", teamAId, draftTimetableAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET/PUT /timetables/{id}/slots（entity由来: teamId が path に無い★BOLA要警戒★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET/PUT /timetables/{id}/slots")
    class SlotEndpoints {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/timetables/{id}/slots", draftTimetableAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAのtimetableIdを直接指定）は一覧403（BOLA）")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/timetables/{id}/slots", draftTimetableAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは一覧200")
        void 正当ADMINは一覧200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/timetables/{id}/slots", draftTimetableAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは更新403")
        void 非ADMINメンバーは更新403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/timetables/{id}/slots", draftTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(slotUpdateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは更新403（BOLA: timetableId直指定での越境更新を防止）")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/timetables/{id}/slots", draftTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(slotUpdateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/timetables/{id}/slots", draftTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(slotUpdateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> slotUpdateBody() {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("dayOfWeek", "MON");
            slot.put("periodNumber", 1);
            slot.put("subjectName", "国語");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("slots", List.of(slot));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. /timetables/{id}/changes（entity由来: teamId が path に無い★BOLA要警戒★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. /timetables/{id}/changes（臨時変更）")
    class ChangeEndpoints {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/timetables/{id}/changes", activeTimetableAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは一覧200")
        void 正当ADMINは一覧200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/timetables/{id}/changes", activeTimetableAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは作成403")
        void 非ADMINメンバーは作成403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/timetables/{id}/changes", activeTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createChangeBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは作成403（BOLA）")
        void 別scopeADMINは作成403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/timetables/{id}/changes", activeTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createChangeBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは作成201")
        void 正当ADMINは作成201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/timetables/{id}/changes", activeTimetableAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createChangeBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("別scope ADMINは更新403（BOLA: changeId直指定での越境更新を防止）")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/timetables/{id}/changes/{changeId}", activeTimetableAId, changeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("subjectName", "理科"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/timetables/{id}/changes/{changeId}", activeTimetableAId, changeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("subjectName", "理科"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別scope ADMINは削除403（BOLA）")
        void 別scopeADMINは削除403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/timetables/{id}/changes/{changeId}", activeTimetableAId, changeAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/timetables/{id}/changes/{changeId}", activeTimetableAId, changeAId))
                    .andExpect(status().isNoContent());
        }

        private Map<String, Object> createChangeBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("targetDate", LocalDate.now().plusDays(2).toString());
            body.put("periodNumber", 2);
            body.put("changeType", "REPLACE");
            body.put("subjectName", "音楽");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. /organizations/{orgId}/timetable-periods（組織スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. /organizations/{orgId}/timetable-periods（コマテンプレート）")
    class PeriodTemplateEndpoints {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/timetable-periods", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は一覧403")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/timetable-periods", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは一覧200")
        void 正当ADMINは一覧200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/timetable-periods", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは一括更新403")
        void 非ADMINメンバーは一括更新403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/timetable-periods", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(periodUpdateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは一括更新403")
        void 別scopeADMINは一括更新403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/timetable-periods", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(periodUpdateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは一括更新200")
        void 正当ADMINは一括更新200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/timetable-periods", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(periodUpdateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> periodUpdateBody() {
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("periodNumber", 1);
            period.put("label", "1限");
            period.put("startTime", "08:45:00");
            period.put("endTime", "09:35:00");
            period.put("isBreak", false);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("periods", List.of(period));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. /teams/{teamId}/timetable-terms（チーム学期・organizationId混同防止）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. /teams/{teamId}/timetable-terms（チーム学期）")
    class TeamTermEndpoints {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetable-terms", teamAId)
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は一覧403")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetable-terms", teamAId)
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMIN・正しいorganizationIdは200")
        void 正当ADMIN正しい組織は200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetable-terms", teamAId)
                            .param("organizationId", orgAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("scope混同防止(BOLA): teamAメンバーがteamAに無関係のorgBIdを指定すると404")
        void 無関係組織ID指定は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/timetable-terms", teamAId)
                            .param("organizationId", orgBId.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは作成403")
        void 非ADMINメンバーは作成403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/timetable-terms", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTermBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは作成403")
        void 別scopeADMINは作成403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/timetable-terms", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTermBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは作成201")
        void 正当ADMINは作成201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/timetable-terms", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createTermBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createTermBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TTAUTHZ 新学期");
            body.put("startDate", LocalDate.of(2025, 10, 1).toString());
            body.put("endDate", LocalDate.of(2025, 12, 31).toString());
            body.put("academicYear", 2025);
            body.put("sortOrder", 2);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. /organizations/{orgId}/timetable-terms（組織学期）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. /organizations/{orgId}/timetable-terms（組織学期）")
    class OrgTermEndpoints {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/timetable-terms", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は一覧403")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/timetable-terms", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/timetable-terms", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは作成403")
        void 非ADMINメンバーは作成403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/timetable-terms", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgTermBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは作成403")
        void 別scopeADMINは作成403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/timetable-terms", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgTermBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/timetable-terms", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createOrgTermBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createOrgTermBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TTAUTHZ 組織新学期");
            body.put("startDate", LocalDate.of(2025, 10, 1).toString());
            body.put("endDate", LocalDate.of(2025, 12, 31).toString());
            body.put("academicYear", 2025);
            body.put("sortOrder", 2);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. /timetable-terms/{termId}（学期共通・entity由来: TEAM/ORGANIZATIONいずれもentityが決める）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. PATCH/DELETE /timetable-terms/{termId}（学期共通）")
    class TermCommonEndpoints {

        @Test
        @DisplayName("チーム学期: 非ADMINメンバーは更新403")
        void チーム学期_非ADMINメンバーは更新403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/timetable-terms/{termId}", termAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改称後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム学期: 別scope ADMIN（teamBのADMINがtermIdを直接指定）は更新403（BOLA）")
        void チーム学期_別scopeADMINは更新403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/timetable-terms/{termId}", termAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改称後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チーム学期: 正当ADMINは更新200")
        void チーム学期_正当ADMINは更新200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/timetable-terms/{termId}", termAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(fullTermUpdateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("チーム学期: 別scope ADMINは削除403（BOLA）")
        void チーム学期_別scopeADMINは削除403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/timetable-terms/{termId}", termAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織学期: 別scope ADMIN（orgBのADMINがtermIdを直接指定）は更新403（BOLA）")
        void 組織学期_別scopeADMINは更新403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(patch("/api/v1/timetable-terms/{termId}", termOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改称後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("組織学期: 正当ADMINは更新200")
        void 組織学期_正当ADMINは更新200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(patch("/api/v1/timetable-terms/{termId}", termOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(fullTermUpdateBody())))
                    .andExpect(status().isOk());
        }

        /**
         * {@code TimetableTermEntity#applyUpdate} は null-safe ではなく
         * name/startDate/endDate/sortOrder を無条件上書きするため（Timetable/Change の
         * applyUpdate とは異なり null 現値維持セマンティクスが無い）、成功系テストでは
         * 全フィールドを埋めた body を使う（部分更新だと startDate/endDate が NULL になり
         * NOT NULL 制約違反で 500 になる。認可根治の対象外の既存挙動のため回避のみ行う）。
         */
        private Map<String, Object> fullTermUpdateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "改称後");
            body.put("startDate", LocalDate.of(2025, 4, 1).toString());
            body.put("endDate", LocalDate.of(2025, 9, 30).toString());
            body.put("sortOrder", 1);
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
                                + "VALUES (:email, 'TTAUTHZ', 'テスト', 'TTAUTHZ テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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

    /**
     * チーム↔組織の ACTIVE 所属を張る（{@code getTeamTerms} の organizationId scope 混同防止検証用）。
     */
    private void insertTeamOrgMembership(Long teamId, Long organizationId) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                                + "VALUES (:teamId, :orgId, 'ACTIVE', NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("orgId", organizationId)
                .executeUpdate();
    }
}
