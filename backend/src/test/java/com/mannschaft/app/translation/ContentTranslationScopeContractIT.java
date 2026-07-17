package com.mannschaft.app.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B10: translation content（{@code ContentTranslationController}）の
 * BOLA是正 API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B10 translation節）。{@code ContentTranslationService#findOrThrow}が
 * 従来 {@code id} の scope 未束縛（{@code findById} のみ）で取得しており、他 team/organization の
 * 翻訳コンテンツIDを path 越しに渡すと get/update/status変更/publish/delete が全て越境実行できる
 * BOLA が成立していた。findOrThrow に scopeType/scopeId 束縛を追加し、不一致は TRANSLATION_002
 * （404）で存在秘匿する。getTranslationForContent/listTranslationsForContent も
 * sourceType+sourceId のみでscope未束縛だったため同様に束縛した。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}。team scope は①非メンバー→403 ②越境ID(BOLA本丸)→404
 * ③正当権限→成功の3象限、organization scope は仕組みが同一（{@code AccessControlService} が
 * scopeType非依存）のため②③の2象限に絞る。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("translation content ドメイン BOLA是正 API 契約テスト（認可根治 Wave3-B10）")
class ContentTranslationScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContentTranslationRepository contentTranslationRepository;

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
    private Long translationTeamBId; // teamB所属
    private Long translationOrgAId;  // orgA所属（BOLA攻撃の標的）
    private Long translationOrgBId;  // orgB所属

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CT認可契約チームA");
        teamBId = insertTeam("CT認可契約チームB");
        orgAId = insertOrganization("CT認可契約組織A");
        orgBId = insertOrganization("CT認可契約組織B");

        adminTeamAId = insertUser("ct-authz-admin-team-a@example.com");
        memberTeamAId = insertUser("ct-authz-member-team-a@example.com");
        adminTeamBId = insertUser("ct-authz-admin-team-b@example.com");
        outsiderId = insertUser("ct-authz-outsider@example.com");

        adminOrgAId = insertUser("ct-authz-admin-org-a@example.com");
        memberOrgAId = insertUser("ct-authz-member-org-a@example.com");
        adminOrgBId = insertUser("ct-authz-admin-org-b@example.com");

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

        translationTeamAId = createTranslation("TEAM", teamAId, "BLOG_POST", 5001L, memberTeamAId);
        translationTeamBId = createTranslation("TEAM", teamBId, "BLOG_POST", 5002L, adminTeamBId);
        translationOrgAId = createTranslation("ORGANIZATION", orgAId, "ANNOUNCEMENT", 6001L, memberOrgAId);
        translationOrgBId = createTranslation("ORGANIZATION", orgBId, "ANNOUNCEMENT", 6002L, adminOrgBId);

        em.flush();
        em.clear();
    }

    private Long createTranslation(String scopeType, Long scopeId, String sourceType, Long sourceId,
                                    Long translatorId) {
        ContentTranslationEntity entity = contentTranslationRepository.save(ContentTranslationEntity.builder()
                .scopeType(scopeType).scopeId(scopeId)
                .sourceType(sourceType).sourceId(sourceId)
                .language("en")
                .translatedTitle("CTタイトル").translatedBody("CT本文").translatedExcerpt("CT抜粋")
                .status("DRAFT")
                .translatorId(translatorId)
                .sourceUpdatedAt(LocalDateTime.now())
                .build());
        return entity.getId();
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: 詳細取得(getTeamTranslation)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: 詳細取得(getTeamTranslation)")
    class GetTeamTranslation {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/{id}", teamAId, translationTeamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境id（他teamのADMINが自teamのURLで他teamのtranslationIdを叩く）は404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/{id}", teamBId, translationTeamAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/{id}", teamAId, translationTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(translationTeamAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: 更新(updateTeamTranslation)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: 更新(updateTeamTranslation)")
    class UpdateTeamTranslation {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/translations/{id}", teamAId, translationTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境idは404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/translations/{id}", teamBId, translationTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("乗っ取り"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/translations/{id}", teamAId, translationTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("更新後タイトル"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("更新後タイトル"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: ステータス変更(changeTeamTranslationStatus)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: ステータス変更(changeTeamTranslationStatus)")
    class ChangeTeamTranslationStatus {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/translations/{id}/status", teamAId, translationTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("IN_REVIEW"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境idは404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/translations/{id}/status", teamBId, translationTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("IN_REVIEW"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/translations/{id}/status", teamAId, translationTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("IN_REVIEW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("IN_REVIEW"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: 公開(publishTeamTranslation)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: 公開(publishTeamTranslation)")
    class PublishTeamTranslation {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/translations/{id}/publish", teamAId, translationTeamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境id（他teamのADMINが自teamのURLで叩く）は404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/translations/{id}/publish", teamBId, translationTeamAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/translations/{id}/publish", teamAId, translationTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: 削除(deleteTeamTranslation)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: 削除(deleteTeamTranslation)")
    class DeleteTeamTranslation {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/translations/{id}", teamAId, translationTeamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境idは404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/translations/{id}", teamBId, translationTeamAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/translations/{id}", teamAId, translationTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // team scope: 原文単位取得(getTeamTranslationForContent) / 一覧(listTeamTranslationsForContent)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("team: 原文単位取得/一覧(getTeamTranslationForContent / listTeamTranslationsForContent)")
    class ContentBasedTeamEndpoints {

        @Test
        @DisplayName("越境sourceId（他teamのADMINが自teamのURLで他teamのcontentId/languageを叩く）は404（BOLA・TRANSLATION_002）")
        void 越境sourceIdの詳細取得は404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/content", teamBId)
                            .param("contentType", "BLOG_POST")
                            .param("contentId", "5001")
                            .param("language", "en"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーの詳細取得は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/content", teamAId)
                            .param("contentType", "BLOG_POST")
                            .param("contentId", "5001")
                            .param("language", "en"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(translationTeamAId));
        }

        @Test
        @DisplayName("越境sourceIdの一覧は空リスト（BOLA・他teamの翻訳が漏洩しない）")
        void 越境sourceIdの一覧は空リスト() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/content/all", teamBId)
                            .param("contentType", "BLOG_POST")
                            .param("contentId", "5001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("正当メンバーの一覧は200")
        void 正当メンバーの一覧は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/translations/content/all", teamAId)
                            .param("contentType", "BLOG_POST")
                            .param("contentId", "5001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(translationTeamAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // organization scope: BOLA(②) + 正当権限(③)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("organization: 詳細取得(getOrgTranslation)")
    class GetOrgTranslation {

        @Test
        @DisplayName("越境idは404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/translations/{id}", orgBId, translationOrgAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/translations/{id}", orgAId, translationOrgAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(translationOrgAId));
        }
    }

    @Nested
    @DisplayName("organization: 更新(updateOrgTranslation)")
    class UpdateOrgTranslation {

        @Test
        @DisplayName("越境idは404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/translations/{id}", orgBId, translationOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("乗っ取り"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/translations/{id}", orgAId, translationOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("更新後タイトル"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("更新後タイトル"));
        }
    }

    @Nested
    @DisplayName("organization: ステータス変更(changeOrgTranslationStatus)")
    class ChangeOrgTranslationStatus {

        @Test
        @DisplayName("越境idは404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/translations/{id}/status", orgBId, translationOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("IN_REVIEW"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/translations/{id}/status", orgAId, translationOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("IN_REVIEW"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("IN_REVIEW"));
        }
    }

    @Nested
    @DisplayName("organization: 公開(publishOrgTranslation)")
    class PublishOrgTranslation {

        @Test
        @DisplayName("越境idは404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/translations/{id}/publish", orgBId, translationOrgAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/translations/{id}/publish", orgAId, translationOrgAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        }
    }

    @Nested
    @DisplayName("organization: 削除(deleteOrgTranslation)")
    class DeleteOrgTranslation {

        @Test
        @DisplayName("越境idは404（BOLA・TRANSLATION_002）")
        void 越境idは404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/translations/{id}", orgBId, translationOrgAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSLATION_002"));
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/translations/{id}", orgAId, translationOrgAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> updateBody(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("version", 0);
        return body;
    }

    private Map<String, Object> statusBody(String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("version", 0);
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
                                + "VALUES (:email, 'CT認可契約', 'テスト', 'CT認可契約テスト', 'ACTIVE', "
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
                                + "CONCAT('ct-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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
                                + "CONCAT('ct-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
