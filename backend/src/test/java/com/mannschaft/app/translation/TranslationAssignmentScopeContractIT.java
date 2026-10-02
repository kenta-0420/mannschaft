package com.mannschaft.app.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.entity.TranslationAssignmentEntity;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import com.mannschaft.app.translation.repository.TranslationAssignmentRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B10: translation assignment（{@code TranslationAssignmentController}）の
 * BOLA是正 API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B10 translation節）。{@code TranslationAssignmentService#assignTranslator}/
 * {@code listAssignments} が {@code translationId} を scope 未束縛（{@code findById} のみ）で解決し、
 * {@code removeAssignment} も {@code id} の存在確認のみ（{@code existsById}）で scope 束縛が無かったため、
 * 他 team/organization の translationId を渡したアサイン作成・一覧閲覧・他 team のアサインIDを渡した
 * 物理削除という BOLA が成立していた。translationId/assignmentId を path scope 配下でのみ解決するよう
 * 束縛し、不一致は TRANSLATION_002（translation）/TRANSLATION_009（assignment）で 404 存在秘匿する。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}。team scope は①非ADMIN/非メンバー→403 ②越境ID(BOLA本丸)→404
 * ③正当権限→成功の3象限、organization scope は②③の2象限に絞る。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("translation assignment ドメイン BOLA是正 API 契約テスト（認可根治 Wave3-B10）")
class TranslationAssignmentScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContentTranslationRepository contentTranslationRepository;

    @Autowired
    private TranslationAssignmentRepository translationAssignmentRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;
    private Long memberTeamAId;
    private Long adminTeamBId;
    private Long outsiderId;

    private Long adminOrgAId;
    private Long memberOrgAId;
    private Long adminOrgBId;

    private Long translationTeamAId; // teamA所属（BOLA攻撃の標的）
    private Long translationOrgAId;  // orgA所属（BOLA攻撃の標的）

    private Long assignmentTeamAId; // teamA所属アサイン（BOLA攻撃の標的）
    private Long assignmentOrgAId;  // orgA所属アサイン（BOLA攻撃の標的）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("TA認可契約チームA");
        teamBId = insertTeam("TA認可契約チームB");
        orgAId = insertOrganization("TA認可契約組織A");
        orgBId = insertOrganization("TA認可契約組織B");

        adminTeamAId = insertUser("ta-authz-admin-team-a@example.com");
        memberTeamAId = insertUser("ta-authz-member-team-a@example.com");
        adminTeamBId = insertUser("ta-authz-admin-team-b@example.com");
        outsiderId = insertUser("ta-authz-outsider@example.com");

        adminOrgAId = insertUser("ta-authz-admin-org-a@example.com");
        memberOrgAId = insertUser("ta-authz-member-org-a@example.com");
        adminOrgBId = insertUser("ta-authz-admin-org-b@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);

        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);

        translationTeamAId = createTranslation("TEAM", teamAId, "BLOG_POST", 7001L);
        translationOrgAId = createTranslation("ORGANIZATION", orgAId, "ANNOUNCEMENT", 8001L);

        assignmentTeamAId = createAssignment("TEAM", teamAId, memberTeamAId, "en");
        assignmentOrgAId = createAssignment("ORGANIZATION", orgAId, memberOrgAId, "en");

        em.flush();
        em.clear();
    }

    private Long createTranslation(String scopeType, Long scopeId, String sourceType, Long sourceId) {
        ContentTranslationEntity entity = contentTranslationRepository.save(ContentTranslationEntity.builder()
                .scopeType(scopeType).scopeId(scopeId)
                .sourceType(sourceType).sourceId(sourceId)
                .language("en")
                .translatedTitle("TAタイトル").translatedBody("TA本文")
                .status("DRAFT")
                .sourceUpdatedAt(LocalDateTime.now())
                .build());
        return entity.getId();
    }

    private Long createAssignment(String scopeType, Long scopeId, Long userId, String language) {
        TranslationAssignmentEntity entity = translationAssignmentRepository.save(TranslationAssignmentEntity.builder()
                .scopeType(scopeType).scopeId(scopeId)
                .userId(userId)
                .language(language)
                .isActive(true)
                .build());
        return entity.getId();
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: アサイン作成(assignTeamTranslator)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: アサイン作成(assignTeamTranslator)")
    class AssignTeamTranslator {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/translations/assignments", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(translationTeamAId, memberTeamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境translationId（他teamのADMINが自teamのURLで他teamのtranslationIdを渡す）は404（BOLA・TRANSLATION_002）")
        void 越境translationIdは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/translations/assignments", teamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(translationTeamAId, adminTeamBId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/translations/assignments", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(translationTeamAId, adminTeamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.scopeId").value(teamAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: アサイン一覧(listTeamAssignments)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: アサイン一覧(listTeamAssignments)")
    class ListTeamAssignments {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/assignments", teamAId)
                            .param("translationId", String.valueOf(translationTeamAId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境translationId（他teamのADMINが自teamのURLで他teamのtranslationIdを渡す）は404（BOLA・TRANSLATION_002）")
        void 越境translationIdは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/assignments", teamBId)
                            .param("translationId", String.valueOf(translationTeamAId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/assignments", teamAId)
                            .param("translationId", String.valueOf(translationTeamAId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(assignmentTeamAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: アサイン削除(removeTeamAssignment)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: アサイン削除(removeTeamAssignment)")
    class RemoveTeamAssignment {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/translations/assignments/{id}",
                            teamAId, assignmentTeamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境assignmentId（他teamのADMINが自teamのURLで他teamのassignmentIdを渡す）は404（BOLA・TRANSLATION_009）")
        void 越境assignmentIdは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/translations/assignments/{id}",
                            teamBId, assignmentTeamAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_009"));
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/translations/assignments/{id}",
                            teamAId, assignmentTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // organization scope: BOLA(②) + 正当権限(③)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("organization: アサイン作成(assignOrgTranslator)")
    class AssignOrgTranslator {

        @Test
        @DisplayName("越境translationIdは404（BOLA・TRANSLATION_002）")
        void 越境translationIdは404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/translations/assignments", orgBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(translationOrgAId, adminOrgBId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/translations/assignments", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(translationOrgAId, adminOrgAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.scopeId").value(orgAId));
        }
    }

    @Nested
    @DisplayName("organization: アサイン一覧(listOrgAssignments)")
    class ListOrgAssignments {

        @Test
        @DisplayName("越境translationIdは404（BOLA・TRANSLATION_002）")
        void 越境translationIdは404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/translations/assignments", orgBId)
                            .param("translationId", String.valueOf(translationOrgAId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/translations/assignments", orgAId)
                            .param("translationId", String.valueOf(translationOrgAId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(assignmentOrgAId));
        }
    }

    @Nested
    @DisplayName("organization: アサイン削除(removeOrgAssignment)")
    class RemoveOrgAssignment {

        @Test
        @DisplayName("越境assignmentIdは404（BOLA・TRANSLATION_009）")
        void 越境assignmentIdは404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/translations/assignments/{id}",
                            orgBId, assignmentOrgAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_009"));
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/translations/assignments/{id}",
                            orgAId, assignmentOrgAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> assignBody(Long translationId, Long assigneeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("translationId", translationId);
        body.put("assigneeId", assigneeId);
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
                                + "VALUES (:email, 'TA認可契約', 'テスト', 'TA認可契約テスト', 'ACTIVE', "
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
                                + "CONCAT('ta-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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
                                + "CONCAT('ta-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
