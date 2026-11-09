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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @DisplayName("B1: 論理削除済みの時間割だけが残るチーム学期は削除できる（204）。学期は論理削除され一覧から消える")
    void 論理削除済み時間割だけが残るチーム学期は削除204() throws Exception {
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
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        // 学期一覧からは消える
        mockMvc.perform(get("/api/v1/teams/{id}/timetable-terms", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // 行は残り（FK 参照元を保つ）、deleted_at が入っている
        Object deletedAt = em.createNativeQuery("SELECT deleted_at FROM timetable_terms WHERE id = :id")
                .setParameter("id", termId).getSingleResult();
        assertThat(deletedAt).isNotNull();
        // 同じ学期を再度削除しても 404（存在しない扱い）
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", termId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("B1: 論理削除した学期と同じ名前・期間で学期を作り直せる（同名検証・日付重複検証から除かれる）")
    void 論理削除した学期と同名同期間で作り直せる() throws Exception {
        Long termId = saveTeamTerm();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", termId))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();

        Map<String, Object> body = termBody();
        body.put("name", "TTTERM 1学期");
        body.put("startDate", LocalDate.of(2025, 4, 1).toString());
        body.put("endDate", LocalDate.of(2025, 9, 30).toString());
        mockMvc.perform(post("/api/v1/teams/{id}/timetable-terms", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("B1: 論理削除した組織学期は組織の学期一覧から消え、組織学期も同じ規則で削除できる")
    void 論理削除した組織学期は一覧から消える() throws Exception {
        TimetableTermEntity orgTerm = termRepository.save(TimetableTermEntity.builder()
                .organizationId(orgId).academicYear(2025).name("TTTERM 組織学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(1).build());
        em.flush();
        em.clear();
        setAuth(adminOrgId);
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", orgTerm.getId()))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();
        mockMvc.perform(get("/api/v1/organizations/{id}/timetable-terms", orgId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("B2: 時間割一覧・詳細の termName に学期名が入る。学期が論理削除された履歴の時間割でも学期名を返す")
    void 時間割応答のtermNameは学期名を返す() throws Exception {
        Long termId = saveTeamTerm();
        Long timetableId = saveTimetable(termId);
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(get("/api/v1/teams/{id}/timetables", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].termName").value("TTTERM 1学期"));
        mockMvc.perform(get("/api/v1/teams/{id}/timetables/{tid}", teamId, timetableId))
                .andExpect(jsonPath("$.data.termName").value("TTTERM 1学期"));

        // 学期を直接論理削除しても（履歴の時間割は残る前提）学期名は引ける
        em.createNativeQuery("UPDATE timetable_terms SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", termId).executeUpdate();
        em.flush();
        em.clear();
        mockMvc.perform(get("/api/v1/teams/{id}/timetables/{tid}", teamId, timetableId))
                .andExpect(jsonPath("$.data.termName").value("TTTERM 1学期"));
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
    @DisplayName("組織学期を参照するチームの時間割（未削除）が残る場合は409、論理削除済みだけなら204")
    void 組織学期を参照する時間割が残れば409() throws Exception {
        TimetableTermEntity orgTerm = termRepository.save(TimetableTermEntity.builder()
                .organizationId(orgId).academicYear(2025).name("TTTERM 組織学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(1).build());
        Long timetableId = saveTimetable(orgTerm.getId());
        em.flush();
        em.clear();

        setAuth(adminOrgId);
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", orgTerm.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TIMETABLE_022"));

        TimetableEntity t = timetableRepository.findById(timetableId).orElseThrow();
        t.softDelete();
        timetableRepository.save(t);
        em.flush();
        em.clear();
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", orgTerm.getId()))
                .andExpect(status().isNoContent());
    }

    // ---- P1: 学期スコープ検証（他チーム・無関係な組織の学期 ID を使わせない） ----

    private String createTimetableBody(Long termId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "TTTERM 越境");
        body.put("termId", termId);
        body.put("effectiveFrom", LocalDate.of(2025, 4, 1).toString());
        body.put("effectiveUntil", LocalDate.of(2025, 9, 30).toString());
        return objectMapper.writeValueAsString(body);
    }

    private org.springframework.test.web.servlet.MvcResult postTimetable(Long termId) throws Exception {
        return mockMvc.perform(post("/api/v1/teams/{id}/timetables", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTimetableBody(termId)))
                .andReturn();
    }

    private void assertSameRejection(org.springframework.test.web.servlet.MvcResult a,
                                     org.springframework.test.web.servlet.MvcResult b) throws Exception {
        assertThat(a.getResponse().getStatus()).isEqualTo(404);
        assertThat(b.getResponse().getStatus()).isEqualTo(a.getResponse().getStatus());
        var ja = objectMapper.readTree(a.getResponse().getContentAsString()).path("error");
        var jb = objectMapper.readTree(b.getResponse().getContentAsString()).path("error");
        assertThat(ja.path("code").asText()).isEqualTo("TIMETABLE_002");
        assertThat(jb.path("code").asText()).isEqualTo(ja.path("code").asText());
        assertThat(jb.path("message").asText()).isEqualTo(ja.path("message").asText());
        assertThat(a.getResponse().getContentAsString()).doesNotContain("越境される");
    }

    @Test
    @DisplayName("P1: 他チームの学期 ID で時間割を作ると、存在しない ID と同じ応答で拒否され、学期名は漏れない")
    void 他チームの学期IDは存在しないIDと同じ応答で拒否() throws Exception {
        Long otherTeamId = insertTeam("TTTERM 他チーム");
        Long otherTermId = termRepository.save(TimetableTermEntity.builder()
                .teamId(otherTeamId).academicYear(2025).name("越境される学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(1).build()).getId();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        assertSameRejection(postTimetable(otherTermId), postTimetable(9_999_999L));
        Number cnt = (Number) em.createNativeQuery("SELECT COUNT(*) FROM timetables WHERE team_id = :t")
                .setParameter("t", teamId).getSingleResult();
        assertThat(cnt.longValue()).isZero();
    }

    @Test
    @DisplayName("P1: 所属していない組織の学期 ID も、存在しない ID と同じ応答で拒否される")
    void 無関係な組織の学期IDは拒否() throws Exception {
        Long strangerOrgId = insertOrganization("TTTERM 無関係な組織");
        Long strangerTermId = termRepository.save(TimetableTermEntity.builder()
                .organizationId(strangerOrgId).academicYear(2025).name("越境される学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(1).build()).getId();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        assertSameRejection(postTimetable(strangerTermId), postTimetable(9_999_999L));
    }

    @Test
    @DisplayName("P1: 自チームの学期と、所属組織の学期では作成できる（201）")
    void 自チームと所属組織の学期は作成できる() throws Exception {
        Long teamTermId = saveTeamTerm();
        Long orgTermId = termRepository.save(TimetableTermEntity.builder()
                .organizationId(orgId).academicYear(2025).name("TTTERM 所属組織の学期")
                .startDate(LocalDate.of(2025, 4, 1)).endDate(LocalDate.of(2025, 9, 30))
                .sortOrder(1).build()).getId();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        assertThat(postTimetable(teamTermId).getResponse().getStatus()).isEqualTo(201);
        assertThat(postTimetable(orgTermId).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("P2: 論理削除済みの学期で時間割は作れない（404 TIMETABLE_002）")
    void 論理削除済みの学期では作れない() throws Exception {
        Long termId = saveTeamTerm();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(delete("/api/v1/timetable-terms/{id}", termId)).andExpect(status().isNoContent());
        em.flush();
        em.clear();
        var result = postTimetable(termId);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ---- D4: チーム単独の学期一覧（organizationId 任意） ----

    @Test
    @DisplayName("D4: organizationId を省いたチーム学期一覧は200で、チーム固有の学期だけを返す")
    void チーム学期一覧_organizationId省略はチーム固有のみ200() throws Exception {
        Long teamTermId = saveTeamTerm();
        termRepository.save(TimetableTermEntity.builder()
                .organizationId(orgId).academicYear(2025).name("TTTERM 組織だけの学期")
                .startDate(LocalDate.of(2025, 10, 1)).endDate(LocalDate.of(2026, 3, 31))
                .sortOrder(1).build());
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(get("/api/v1/teams/{id}/timetable-terms", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(teamTermId))
                .andExpect(jsonPath("$.data[0].scope").value("TEAM"));
    }

    @Test
    @DisplayName("D4: organizationId を省いても非メンバーは403")
    void チーム学期一覧_organizationId省略でも非メンバーは403() throws Exception {
        Long outsider = insertUser("ttterm-outsider@example.com");
        em.flush();
        setAuth(outsider);
        mockMvc.perform(get("/api/v1/teams/{id}/timetable-terms", teamId))
                .andExpect(status().isForbidden());
    }

    // ---- A2: 開始日と終了日の前後 ----

    @Test
    @DisplayName("A2: チーム学期の作成で開始日が終了日より後なら400、fieldErrorsはendDate")
    void チーム学期作成_開始日が終了日より後は400() throws Exception {
        setAuth(adminTeamId);
        Map<String, Object> body = termBody();
        body.put("startDate", LocalDate.of(2025, 12, 31).toString());
        body.put("endDate", LocalDate.of(2025, 10, 1).toString());
        mockMvc.perform(post("/api/v1/teams/{id}/timetable-terms", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("endDate"));
    }

    @Test
    @DisplayName("A2: 開始日と終了日が同日なら400（DB の CHECK は start_date < end_date）")
    void チーム学期作成_開始日と終了日が同日は400() throws Exception {
        setAuth(adminTeamId);
        Map<String, Object> body = termBody();
        body.put("startDate", LocalDate.of(2025, 10, 1).toString());
        body.put("endDate", LocalDate.of(2025, 10, 1).toString());
        mockMvc.perform(post("/api/v1/teams/{id}/timetable-terms", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("endDate"));
    }

    @Test
    @DisplayName("A2: 組織学期の作成で開始日が終了日より後なら400")
    void 組織学期作成_開始日が終了日より後は400() throws Exception {
        setAuth(adminOrgId);
        Map<String, Object> body = termBody();
        body.put("startDate", LocalDate.of(2025, 12, 31).toString());
        body.put("endDate", LocalDate.of(2025, 10, 1).toString());
        mockMvc.perform(post("/api/v1/organizations/{id}/timetable-terms", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("endDate"));
    }

    @Test
    @DisplayName("A2: PATCH で終了日だけを既存の開始日より前にすると400")
    void 学期更新_終了日を開始日より前にすると400() throws Exception {
        Long termId = saveTeamTerm();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(patch("/api/v1/timetable-terms/{id}", termId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\":\"2025-03-31\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("endDate"));
    }

    @Test
    @DisplayName("A7: PATCH の academicYear も作成と同じく2000〜2100の範囲外は400")
    void 学期更新_academicYear範囲外は400() throws Exception {
        Long termId = saveTeamTerm();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        mockMvc.perform(patch("/api/v1/timetable-terms/{id}", termId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"academicYear\":27}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("academicYear"));
    }

    @Test
    @DisplayName("A2: 日付範囲が既存の学期と重なると409 TIMETABLE_021")
    void チーム学期作成_日付範囲の重複は409() throws Exception {
        saveTeamTerm();
        em.flush();
        em.clear();
        setAuth(adminTeamId);
        Map<String, Object> body = termBody();
        body.put("startDate", LocalDate.of(2025, 9, 1).toString());
        mockMvc.perform(post("/api/v1/teams/{id}/timetable-terms", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TIMETABLE_021"));
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
