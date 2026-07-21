package com.mannschaft.app.school;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.school.repository.StudentAttendanceSummaryRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6 — school 出席集計（{@code AttendanceSummaryController}）認可契約テスト（試練）。
 *
 * <p>{@code AttendanceSummaryController} の全 3EP は認可皆無で、呼び出し元の身元（currentUserId）を
 * {@code AttendanceSummaryService} に渡してすらいなかったため、認可判定が原理的に不可能な状態だった。
 * 出席集計は児童の PII（出席率・欠席日数・保健室登校日数等）であり、非メンバーが teamId / studentId を
 * 推測するだけで閲覧・再計算できた。</p>
 *
 * <p>金型: {@code SchoolAttendanceScopeContractIT}（同ドメインの Wave5 第一陣A）
 * ＋ {@code DailyAttendanceController} / {@code DailyAttendanceService} の既存認可パターン
 * （controller が {@code SecurityUtils.getCurrentUserId()} を取得 → service が
 * {@code accessControlService.checkMembership(currentUserId, teamId, "TEAM")}）。</p>
 *
 * <p>認可モデル: 3EP とも teamId（パス or クエリ or リクエストボディ）でスコープが明示されるため、
 * {@code checkMembership} により非メンバーは 403（COMMON_002）。生徒個別集計は
 * {@code (studentUserId, teamId)} の複合キーで引くため、自チームの teamId で他チームの生徒を
 * 指定しても行がヒットせず SUMMARY_NOT_FOUND に収束する（スコープとリソースが束縛済み）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("school 出席集計 認可契約テスト（Wave6 試練）")
class SchoolAttendanceSummaryScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudentAttendanceSummaryRepository summaryRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long teacherAId;   // teamA の正当メンバー（担任相当）
    private Long studentAId;   // teamA に所属する生徒（集計対象）
    private Long memberBId;    // teamB のみに所属する越境攻撃者
    private Long outsiderId;   // どこにも所属しない非メンバー

    private static final short ACADEMIC_YEAR = 2025;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SUMAUTHZ チームA");
        teamBId = insertTeam("SUMAUTHZ チームB");

        teacherAId = insertUser("sumauthz-teacher-a@example.com");
        studentAId = insertUser("sumauthz-student-a@example.com");
        memberBId = insertUser("sumauthz-member-b@example.com");
        outsiderId = insertUser("sumauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, teacherAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, studentAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        // teamA・studentA の集計行を用意（正当ケースが 200 になるための前提データ）。
        summaryRepository.save(StudentAttendanceSummaryEntity.builder()
                .teamId(teamAId)
                .studentUserId(studentAId)
                .academicYear(ACADEMIC_YEAR)
                .periodFrom(LocalDate.of(2025, 4, 1))
                .periodTo(LocalDate.of(2026, 3, 31))
                .build());

        em.flush();
        em.clear();
    }

    private String studentSummary(Long studentId) {
        return "/api/v1/students/" + studentId + "/attendance/summary";
    }

    private String classSummaries(Long teamId) {
        return "/api/v1/teams/" + teamId + "/attendance/summaries";
    }

    private String recalculate(Long studentId) {
        return "/api/v1/students/" + studentId + "/attendance/summary/recalculate";
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /students/{studentId}/attendance/summary（生徒出席集計・checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET 生徒出席集計（checkMembership）")
    class GetStudentSummary {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(studentSummary(studentAId))
                            .param("teamId", String.valueOf(teamAId))
                            .param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は403")
        void 越境は403() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(get(studentSummary(studentAId))
                            .param("teamId", String.valueOf(teamAId))
                            .param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(get(studentSummary(studentAId))
                            .param("teamId", String.valueOf(teamAId))
                            .param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("生徒本人は自分の集計を200で取得できる")
        void 本人は200() throws Exception {
            setAuth(studentAId);
            mockMvc.perform(get(studentSummary(studentAId))
                            .param("teamId", String.valueOf(teamAId))
                            .param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /teams/{teamId}/attendance/summaries（クラス集計一覧・checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET クラス出席集計一覧（checkMembership）")
    class GetClassSummaries {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(classSummaries(teamAId))
                            .param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は403")
        void 越境は403() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(get(classSummaries(teamAId))
                            .param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(get(classSummaries(teamAId))
                            .param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /students/{studentId}/attendance/summary/recalculate（再計算・checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST 生徒出席集計再計算（checkMembership）")
    class Recalculate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(recalculate(studentAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(recalcBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は403")
        void 越境は403() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(post(recalculate(studentAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(recalcBody(teamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは201")
        void 正当メンバーは201() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post(recalculate(studentAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(recalcBody(teamAId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 再計算リクエストボディ。
     *
     * <p>{@code RecalculateSummaryRequest} の {@code @NotNull} 項目（teamId / academicYear /
     * periodFrom / periodTo）をすべて充足させる。{@code @Valid} は認可ガードより先に走るため、
     * 項目を欠くと bind 時 400 になり 403 の検証に到達しない。</p>
     */
    private Map<String, Object> recalcBody(Long teamId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamId);
        body.put("academicYear", ACADEMIC_YEAR);
        body.put("periodFrom", LocalDate.of(2025, 4, 1).toString());
        body.put("periodTo", LocalDate.of(2026, 3, 31).toString());
        return body;
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
                                + "VALUES (:email, 'SUMAUTHZ', 'テスト', 'SUMAUTHZ テスト', 'ACTIVE', "
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
}
