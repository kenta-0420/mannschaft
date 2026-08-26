package com.mannschaft.app.school;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity;
import com.mannschaft.app.school.entity.AttendanceRequirementEvaluationEntity.EvaluationStatus;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.RequirementCategory;
import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import com.mannschaft.app.school.repository.AttendanceRequirementEvaluationRepository;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6 — school 出席要件評価・出欠統計の認可契約テスト。
 *
 * <p>対象は {@code AttendanceRequirementEvaluationController}（4EP）と
 * {@code AttendanceStatisticsController}（3EP）。変更前の状態に関する詳細はマージ後に戦役台帳へ記録する。</p>
 *
 * <p>金型: {@code SchoolAttendanceScopeContractIT}（同ドメイン Wave5 第一陣A）。
 * 認可の敷き方は同ドメインの {@code AttendanceRequirementService} /
 * {@code AttendanceLocationService} / {@code DailyAttendanceService} を踏襲する。</p>
 *
 * <p>敷設後の認可モデル:</p>
 * <ul>
 *   <li><b>スコープ宣言型 EP</b>（URL パスに {@code teamId} を持つ at-risk 一覧・月次統計・CSV エクスポート）:
 *       {@code checkMembership} により非メンバーは 403（COMMON_002）。</li>
 *   <li><b>bare id EP</b>（URL に scope を含まない evaluate / resolve）: 対象 entity を fetch し、
 *       規程 entity 由来スコープ（{@code organizationId} 非 null なら ORGANIZATION、そうでなければ TEAM）の
 *       メンバーであることを要求。権限が無ければ 404 で存在秘匿（規程側=S030 / 評価側=S034）。</li>
 *   <li><b>生徒個別 EP</b>（評価一覧）: 本人／規程スコープ所属の教職員／ACTIVE な careLink を持つ保護者の
 *       三経路。全経路失敗で 403（COMMON_002）。</li>
 *   <li><b>{@code /me} 系 EP</b>（期間別統計）: リポジトリ引きが閲覧者本人と複合キー化されており
 *       自己スコープで閉じているため、他人の teamId を指定しても他人の記録は返らない。</li>
 * </ul>
 *
 * <p>未カバー: 保護者経路の正常系（ACTIVE な careLink 行の seed ヘルパーが未整備のため、
 * 本テストでは careLink 不在＝403 側のみを固定している）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("school 出席要件評価・出欠統計 認可契約テスト（Wave6）")
class SchoolAttendanceEvaluationScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AttendanceRequirementRuleRepository ruleRepository;

    @Autowired
    private AttendanceRequirementEvaluationRepository evaluationRepository;

    @Autowired
    private StudentAttendanceSummaryRepository summaryRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long teacherAId;   // teamA の正当メンバー（担任相当）
    private Long studentAId;   // teamA に所属する生徒（評価対象）
    private Long memberBId;    // teamB のみに所属する越境者
    private Long outsiderId;   // どこにも所属しない非メンバー

    private Long ruleAId;         // teamA スコープの規程
    private Long evaluationAId;   // studentA × ruleA の未解消評価

    private static final short ACADEMIC_YEAR = 2025;
    private static final LocalDate FROM = LocalDate.of(2025, 4, 1);
    private static final LocalDate TO = LocalDate.of(2025, 4, 30);

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("EVALAUTHZ チームA");
        teamBId = insertTeam("EVALAUTHZ チームB");

        teacherAId = insertUser("evalauthz-teacher-a@example.com");
        studentAId = insertUser("evalauthz-student-a@example.com");
        memberBId = insertUser("evalauthz-member-b@example.com");
        outsiderId = insertUser("evalauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, teacherAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, studentAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        ruleAId = ruleRepository.save(AttendanceRequirementRuleEntity.builder()
                .teamId(teamAId).academicYear(ACADEMIC_YEAR)
                .category(RequirementCategory.GRADE_PROMOTION).name("EVALAUTHZ チームA規程")
                .minAttendanceRate(new BigDecimal("80.00"))
                .effectiveFrom(LocalDate.now().minusDays(1)).build()).getId();

        // evaluate の正常系が SUMMARY_NOT_FOUND にならないよう集計行を用意する
        Long summaryId = summaryRepository.save(StudentAttendanceSummaryEntity.builder()
                .teamId(teamAId).studentUserId(studentAId).termId(null)
                .academicYear(ACADEMIC_YEAR)
                .periodFrom(FROM).periodTo(TO)
                .totalSchoolDays((short) 100).presentDays((short) 90).absentDays((short) 10)
                .attendanceRate(new BigDecimal("90.00"))
                .build()).getId();

        evaluationAId = evaluationRepository.save(AttendanceRequirementEvaluationEntity.builder()
                .requirementRuleId(ruleAId)
                .studentUserId(studentAId)
                .summaryId(summaryId)
                .status(EvaluationStatus.VIOLATION)
                .currentAttendanceRate(new BigDecimal("75.00"))
                .remainingAllowedAbsences(0)
                .evaluatedAt(LocalDateTime.now().minusDays(1))
                .build()).getId();

        em.flush();
        em.clear();
    }

    private String studentEvaluations(Long studentId) {
        return "/api/v1/students/" + studentId + "/attendance/requirements/evaluations";
    }

    private String atRisk(Long teamId) {
        return "/api/v1/teams/" + teamId + "/attendance/requirements/at-risk";
    }

    private String evaluate(Long studentId, Long ruleId) {
        return "/api/v1/students/" + studentId + "/attendance/requirements/" + ruleId + "/evaluate";
    }

    private String resolve(Long evaluationId) {
        return "/api/v1/attendance/requirements/evaluations/" + evaluationId + "/resolve";
    }

    private String monthly(Long teamId) {
        return "/api/v1/teams/" + teamId + "/attendance/statistics/monthly";
    }

    private String export(Long teamId) {
        return "/api/v1/teams/" + teamId + "/attendance/export";
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /students/{studentId}/attendance/requirements/evaluations（三経路）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET 生徒の評価一覧（本人／教職員／保護者の三経路）")
    class GetStudentEvaluations {

        @Test
        @DisplayName("非メンバー（careLinkなし）は403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(studentEvaluations(studentAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は403")
        void 越境は403() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(get(studentEvaluations(studentAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("本人は200")
        void 本人は200() throws Exception {
            setAuth(studentAId);
            mockMvc.perform(get(studentEvaluations(studentAId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("同一チーム所属の教職員は200")
        void 同一チーム教職員は200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(get(studentEvaluations(studentAId)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /teams/{teamId}/attendance/requirements/at-risk（checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET チームのリスク生徒一覧（checkMembership）")
    class GetAtRiskStudents {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(atRisk(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は403")
        void 越境は403() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(get(atRisk(teamAId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(get(atRisk(teamAId)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /students/{studentId}/attendance/requirements/{ruleId}/evaluate
    //    （規程 entity 由来スコープ・404 存在秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST 評価実行（ruleId 由来スコープ・404 存在秘匿）")
    class Evaluate {

        @Test
        @DisplayName("非メンバーは404（存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(evaluate(studentAId, ruleAId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は404（存在秘匿）")
        void 越境は404() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(post(evaluate(studentAId, ruleAId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("存在しないruleIdは404")
        void 存在しないIDは404() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post(evaluate(studentAId, 999_999_999L)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは201")
        void 正当メンバーは201() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post(evaluate(studentAId, ruleAId)))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /attendance/requirements/evaluations/{evaluationId}/resolve
    //    （評価→規程 entity 由来スコープ・404 存在秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST 違反解消（evaluationId 由来スコープ・404 存在秘匿）")
    class ResolveViolation {

        @Test
        @DisplayName("非メンバーは404（存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(resolve(evaluationAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は404（存在秘匿）")
        void 越境は404() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(post(resolve(evaluationAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("存在しないevaluationIdは404")
        void 存在しないIDは404() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post(resolve(999_999_999L))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(post(resolve(evaluationAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET /teams/{teamId}/attendance/statistics/monthly（checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET 月次出欠集計（checkMembership）")
    class GetMonthlyStatistics {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(monthly(teamAId)).param("year", "2025").param("month", "4"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は403")
        void 越境は403() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(get(monthly(teamAId)).param("year", "2025").param("month", "4"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(get(monthly(teamAId)).param("year", "2025").param("month", "4"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET /teams/{teamId}/attendance/export（checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET 出欠CSVエクスポート（checkMembership）")
    class ExportCsv {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(export(teamAId))
                            .param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームメンバー（越境）は403")
        void 越境は403() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(get(export(teamAId))
                            .param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(teacherAId);
            mockMvc.perform(get(export(teamAId))
                            .param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET /me/attendance/statistics/term（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET 期間別出欠集計（/me・自己スコープ）")
    class GetTermStatistics {

        @Test
        @DisplayName("本人は200")
        void 本人は200() throws Exception {
            setAuth(studentAId);
            mockMvc.perform(get("/api/v1/me/attendance/statistics/term")
                            .param("teamId", teamAId.toString())
                            .param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他人の teamId を指定しても自分の記録しか返らない（自己スコープ・200）")
        void 他人のteamIdでも自己スコープに閉じる() throws Exception {
            setAuth(memberBId);
            mockMvc.perform(get("/api/v1/me/attendance/statistics/term")
                            .param("teamId", teamAId.toString())
                            .param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.data.studentUserId").value(memberBId))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.data.totalSchoolDays").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> resolveBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolutionNote", "EVALAUTHZ 保護者と面談し指導完了");
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
                                + "VALUES (:email, 'EVALAUTHZ', 'テスト', 'EVALAUTHZ テスト', 'ACTIVE', "
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
