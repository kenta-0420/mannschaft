package com.mannschaft.app.timetable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 時間割の学期 API 契約テスト（CMP-261105-1507）。
 *
 * <ul>
 *   <li>③ 学期作成で academicYear 未指定は 400（DB の academic_year は NOT NULL で既定値の意味が無い）、
 *       sortOrder 未指定は DB 既定の 0 で 201。500 を漏らさない。</li>
 *   <li>④ 論理削除済みの時間割が残る学期の削除は FK fk_tm_term(RESTRICT) 違反ではなく
 *       409 TIMETABLE_022 を返す。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("時間割 学期 API 契約テスト（academicYear/sortOrder 検証・削除時の参照保護）")
class TimetableTermContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TimetableRepository timetableRepository;

    @Autowired
    private TimetableTermRepository termRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long orgId;
    private Long adminTeamId;
    private Long adminOrgId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("TTTERM チーム");
        orgId = insertOrganization("TTTERM 組織");
        insertTeamOrgMembership(teamId, orgId);
        adminTeamId = insertUser("ttterm-admin-team@example.com");
        adminOrgId = insertUser("ttterm-admin-org@example.com");
        MembershipTestHelper.insertMembership(em, adminTeamId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, adminOrgId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgId, "ADMIN", null, orgId);
        em.flush();
        em.clear();
    }

    // ---- ③ 作成 ----

    @Test
    @DisplayName("チーム学期: academicYear 未指定は400")
    void チーム学期_academicYear未指定は400() throws Exception {
        setAuth(adminTeamId);
        Map<String, Object> body = termBody();
        body.remove("academicYear");
        mockMvc.perform(post("/api/v1/teams/{id}/timetable-terms", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("チーム学期: sortOrder 未指定は201（既定0）")
    void チーム学期_sortOrder未指定は201() throws Exception {
        setAuth(adminTeamId);
        Map<String, Object> body = termBody();
        body.remove("sortOrder");
        mockMvc.perform(post("/api/v1/teams/{id}/timetable-terms", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        em.flush();
    }

    @Test
    @DisplayName("組織学期: academicYear 未指定は400")
    void 組織学期_academicYear未指定は400() throws Exception {
        setAuth(adminOrgId);
        Map<String, Object> body = termBody();
        body.remove("academicYear");
        mockMvc.perform(post("/api/v1/organizations/{id}/timetable-terms", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("組織学期: sortOrder 未指定は201（既定0）")
    void 組織学期_sortOrder未指定は201() throws Exception {
        setAuth(adminOrgId);
        Map<String, Object> body = termBody();
        body.remove("sortOrder");
        mockMvc.perform(post("/api/v1/organizations/{id}/timetable-terms", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        em.flush();
    }

    @Test
    @DisplayName("学期更新: sortOrder を省いた部分更新でも500にならず既存値を保つ")
    void 学期更新_sortOrder省略は既存値保持() throws Exception {
        Long termId = saveTeamTerm();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(patch("/api/v1/timetable-terms/{id}", termId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TTTERM 改名\"}"))
                .andExpect(status().isOk());
        em.flush();
        em.clear();
        assertThat(termRepository.findById(termId).orElseThrow().getSortOrder()).isEqualTo(3);
    }

    // ---- ④ 削除 ----

    @Test
    @DisplayName("論理削除済みの時間割だけが残るチーム学期の削除は409 TIMETABLE_022")
    void 論理削除済み時間割が残るチーム学期の削除は409() throws Exception {
        Long termId = saveTeamTerm();
        Long timetableId = saveTimetable(termId);
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(delete("/api/v1/teams/{id}/timetables/{tid}", teamId, timetableId))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", termId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TIMETABLE_022"));
        em.flush();
    }

    @Test
    @DisplayName("時間割が残るチーム学期の削除は409 TIMETABLE_022")
    void 時間割が残るチーム学期の削除は409() throws Exception {
        Long termId = saveTeamTerm();
        saveTimetable(termId);
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", termId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TIMETABLE_022"));
    }

    @Test
    @DisplayName("時間割が一件も無い学期は削除できる（204）")
    void 参照なしの学期は削除204() throws Exception {
        Long termId = saveTeamTerm();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", termId))
                .andExpect(status().isNoContent());
        em.flush();
    }

    @Test
    @DisplayName("組織学期を参照するチームの時間割（論理削除済み）が残る場合も409")
    void 組織学期を参照する時間割が残れば409() throws Exception {
        TimetableTermEntity orgTerm = termRepository.save(TimetableTermEntity.builder()
                .organizationId(orgId).academicYear(2025).name("TTTERM 組織学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(1).build());
        Long timetableId = saveTimetable(orgTerm.getId());
        em.flush();
        em.clear();
        TimetableEntity t = timetableRepository.findById(timetableId).orElseThrow();
        t.softDelete();
        timetableRepository.save(t);
        em.flush();
        em.clear();

        setAuth(adminOrgId);
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", orgTerm.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TIMETABLE_022"));
    }

    // ---- helpers ----

    private Map<String, Object> termBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "TTTERM 新学期");
        body.put("startDate", LocalDate.of(2025, 10, 1).toString());
        body.put("endDate", LocalDate.of(2025, 12, 31).toString());
        body.put("academicYear", 2025);
        body.put("sortOrder", 2);
        return body;
    }

    private Long saveTeamTerm() {
        return termRepository.save(TimetableTermEntity.builder()
                .teamId(teamId).academicYear(2025).name("TTTERM 1学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(3).build()).getId();
    }

    private Long saveTimetable(Long termId) {
        return timetableRepository.save(TimetableEntity.builder()
                .teamId(teamId).termId(termId).name("TTTERM 時間割")
                .status(TimetableStatus.DRAFT).visibility(TimetableVisibility.MEMBERS_ONLY)
                .effectiveFrom(LocalDate.of(2025, 4, 1)).effectiveUntil(LocalDate.of(2025, 9, 30))
                .weekPatternEnabled(false).build()).getId();
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
                                + "VALUES (:email, 'TTTERM', 'テスト', 'TTTERM テスト', 'ACTIVE', "
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

    private void insertTeamOrgMembership(Long teamId, Long organizationId) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                                + "VALUES (:teamId, :orgId, 'ACTIVE', NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("orgId", organizationId)
                .executeUpdate();
    }
}
