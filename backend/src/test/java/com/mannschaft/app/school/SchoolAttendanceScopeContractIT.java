package com.mannschaft.app.school;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.RequirementCategory;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave5 第一陣A — school 出席要件規程 CRUD（{@code AttendanceRequirementController}）認可契約テスト（試練）。
 *
 * <p>{@code AttendanceRequirementController} の全 6EP は認可皆無で、{@code AttendanceRequirementService} の
 * CRUD も {@code findById(ruleId)} のみで membership/admin を検証していなかった。金型:
 * {@code FacilityOrgScopeContractIT} / {@code MemberScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext）。</p>
 *
 * <p>認可モデル（school 出席要件規程の設計判断）:</p>
 * <ul>
 *   <li><b>スコープ宣言型 EP</b>（一覧/作成。URL パスが {@code orgId}/{@code teamId} を明示）:
 *       read={@code checkMembership} / write={@code checkAdminOrAbove}。非メンバーは 403（COMMON_002）。
 *       スコープ自体は秘匿不要（呼び出し元が既に scopeId を知っている）。</li>
 *   <li><b>ruleId 直指定 EP</b>（更新/削除。URL に scope を含まない bare id）: entity を fetch → entity 由来
 *       スコープ（{@code organizationId} 非 null なら ORGANIZATION、そうでなければ TEAM）で {@code isAdminOrAbove}
 *       を判定し、権限が無ければ 404（{@code REQUIREMENT_RULE_NOT_FOUND}）で存在秘匿する。非所属の部外者も
 *       別団体の正規 ADMIN による越境も、対象スコープへの権限が無い点は区別不能なため同一の 404 に収束する。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("school 出席要件規程 CRUD 認可契約テスト（Wave5 第一陣A 試練）")
class SchoolAttendanceScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AttendanceRequirementRuleRepository ruleRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long teamAId;
    private Long teamBId;

    private Long orgAdminAId;  // orgA の ADMIN（正当）
    private Long teamAdminAId;  // teamA の ADMIN（正当）
    private Long teamAdminBId;  // teamB の ADMIN（別 scope の越境攻撃者）
    private Long memberAId;     // teamA/orgA の非 ADMIN メンバー
    private Long outsiderId;    // どこにも所属しない非メンバー

    private Long orgRuleAId;    // orgA スコープの規程
    private Long teamRuleAId;   // teamA スコープの規程
    private Long teamRuleBId;   // teamB スコープの規程（越境アクセステスト用）

    private static final short ACADEMIC_YEAR = 2025;

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("SCHAUTHZ 組織A");
        teamAId = insertTeam("SCHAUTHZ チームA");
        teamBId = insertTeam("SCHAUTHZ チームB");

        orgAdminAId = insertUser("schauthz-org-admin-a@example.com");
        teamAdminAId = insertUser("schauthz-team-admin-a@example.com");
        teamAdminBId = insertUser("schauthz-team-admin-b@example.com");
        memberAId = insertUser("schauthz-member-a@example.com");
        outsiderId = insertUser("schauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership/isMember（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, orgAdminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, teamAdminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, teamAdminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminBId, "ADMIN", teamBId, null);
        // memberAId は orgA と teamA の非 ADMIN メンバー
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        orgRuleAId = ruleRepository.save(AttendanceRequirementRuleEntity.builder()
                .organizationId(orgAId).academicYear(ACADEMIC_YEAR)
                .category(RequirementCategory.GRADE_PROMOTION).name("SCHAUTHZ 組織A規程")
                .effectiveFrom(LocalDate.now().minusDays(1)).build()).getId();

        teamRuleAId = ruleRepository.save(AttendanceRequirementRuleEntity.builder()
                .teamId(teamAId).academicYear(ACADEMIC_YEAR)
                .category(RequirementCategory.GRADE_PROMOTION).name("SCHAUTHZ チームA規程")
                .effectiveFrom(LocalDate.now().minusDays(1)).build()).getId();

        teamRuleBId = ruleRepository.save(AttendanceRequirementRuleEntity.builder()
                .teamId(teamBId).academicYear(ACADEMIC_YEAR)
                .category(RequirementCategory.GRADE_PROMOTION).name("SCHAUTHZ チームB規程")
                .effectiveFrom(LocalDate.now().minusDays(1)).build()).getId();

        em.flush();
        em.clear();
    }

    private String orgRules(Long orgId) {
        return "/api/v1/organizations/" + orgId + "/attendance-requirements";
    }

    private String teamRules(Long teamId) {
        return "/api/v1/teams/" + teamId + "/attendance-requirements";
    }

    private String rule(Long ruleId) {
        return "/api/v1/attendance-requirements/" + ruleId;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /organizations/{orgId}/attendance-requirements（組織一覧・checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET 組織スコープ規程一覧（checkMembership）")
    class ListOrgRules {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(orgRules(orgAId)).param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別組織所属なし（別チームADMIN）は403")
        void 越境は403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(get(orgRules(orgAId)).param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(orgRules(orgAId)).param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /organizations/{orgId}/attendance-requirements（組織作成・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST 組織スコープ規程作成（checkAdminOrAbove）")
    class CreateOrgRule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(orgRules(orgAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別組織ADMIN（越境）は403")
        void 越境は403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(post(orgRules(orgAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(post(orgRules(orgAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /teams/{teamId}/attendance-requirements（チーム一覧・checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET チームスコープ規程一覧（checkMembership）")
    class ListTeamRules {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(teamRules(teamAId)).param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は403")
        void 越境は403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(get(teamRules(teamAId)).param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(teamRules(teamAId)).param("academicYear", String.valueOf(ACADEMIC_YEAR)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /teams/{teamId}/attendance-requirements（チーム作成・checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST チームスコープ規程作成（checkAdminOrAbove）")
    class CreateTeamRule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post(teamRules(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は403")
        void 越境は403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(post(teamRules(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post(teamRules(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. PATCH /attendance-requirements/{ruleId}（更新・ruleId 由来スコープ404存在秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. PATCH 規程更新（ruleId 由来スコープ・404 存在秘匿）")
    class UpdateRule {

        @Test
        @DisplayName("別チームADMIN（越境・teamA規程）は404")
        void 越境は404() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(patch(rule(teamRuleAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバー（同一スコープ）は404（存在秘匿）")
        void 非ADMINメンバーは404() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch(rule(teamRuleAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("存在しないruleIdは404")
        void 存在しないIDは404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(patch(rule(999_999_999L))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMIN（チームスコープ）は200")
        void 正当チームADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(patch(rule(teamRuleAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMIN（組織スコープ）は200")
        void 正当組織ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(patch(rule(orgRuleAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. DELETE /attendance-requirements/{ruleId}（削除・ruleId 由来スコープ404存在秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. DELETE 規程削除（ruleId 由来スコープ・404 存在秘匿）")
    class DeleteRule {

        @Test
        @DisplayName("別チームADMIN（越境・teamA規程）は404")
        void 越境は404() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(delete(rule(teamRuleAId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバー（同一スコープ）は404（存在秘匿）")
        void 非ADMINメンバーは404() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete(rule(teamRuleAId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMIN（チームスコープ）は204")
        void 正当チームADMINは204() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(delete(rule(teamRuleAId)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("正当ADMIN（組織スコープ）は204")
        void 正当組織ADMINは204() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(delete(rule(orgRuleAId)))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> createBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("academicYear", ACADEMIC_YEAR);
        body.put("category", "GRADE_PROMOTION");
        body.put("name", "SCHAUTHZ 新規規程" + System.nanoTime());
        body.put("effectiveFrom", LocalDate.now().toString());
        return body;
    }

    private Map<String, Object> updateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "SCHAUTHZ 更新後規程");
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
                                + "VALUES (:email, 'SCHAUTHZ', 'テスト', 'SCHAUTHZ テスト', 'ACTIVE', "
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
}
