package com.mannschaft.app.knowledgebase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.entity.KbTemplateEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import com.mannschaft.app.knowledgebase.repository.KbTemplateRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B10: knowledgebase page 親子束縛（{@code KbPageController#createPage/movePage}）の
 * BOLA是正 API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B10 knowledgebase節）。{@code KbPageService#createPage} の
 * {@code parentId}/{@code templateId}、{@code movePage} の {@code newParentId} が
 * 従来 scope 未束縛（{@code findByIdAndDeletedAtIsNull}/{@code templateRepository.findById} のみ）で
 * 解決されており、他 team の parentId へぶら下げる／他 team のテンプレートを流用する／
 * 他 team 配下へ移動する BOLA が成立していた。同一 scope 内でのみ解決するよう束縛し、
 * 不一致は KB_001（page）/ KB_010（template）で 404 存在秘匿する。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}。</p>
 *
 * <p><b>象限</b>: ①非メンバー→403 ②越境ID（他teamのparentId/templateId/newParentId・BOLA本丸）→404
 * ③正当ADMIN・自team内ID→成功。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("knowledgebase page 親子束縛 BOLA是正 API 契約テスト（認可根治 Wave3-B10）")
class KbPageBolaScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KbPageRepository kbPageRepository;

    @Autowired
    private KbTemplateRepository kbTemplateRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long outsiderId;

    private Long pageAId;   // teamA 所属ページ（parentId/newParentId 越境攻撃の標的）
    private Long pageBId;   // teamB 所属ページ（move元）
    private Long templateAId; // teamA 所属テンプレート（scope固有・非SYSTEM）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("KBPAGE認可契約チームA");
        teamBId = insertTeam("KBPAGE認可契約チームB");

        adminAId = insertUser("kbpage-authz-admin-a@example.com");
        adminBId = insertUser("kbpage-authz-admin-b@example.com");
        outsiderId = insertUser("kbpage-authz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);

        pageAId = createPage(teamAId, "kbpage-page-a", adminAId);
        pageBId = createPage(teamBId, "kbpage-page-b", adminBId);

        KbTemplateEntity templateA = kbTemplateRepository.save(KbTemplateEntity.builder()
                .scopeType("TEAM").scopeId(teamAId)
                .name("KBPAGEテンプレA")
                .body("テンプレ本文A")
                .isSystem(false)
                .createdBy(adminAId)
                .build());
        templateAId = templateA.getId();

        em.flush();
        em.clear();
    }

    private Long createPage(Long teamId, String slug, Long createdBy) {
        KbPageEntity page = kbPageRepository.save(KbPageEntity.builder()
                .scopeType("TEAM").scopeId(teamId)
                .path("/0").depth(0)
                .title("KBPAGEテストページ").slug(slug)
                .body("本文")
                .accessLevel(PageAccessLevel.ALL_MEMBERS)
                .status(PageStatus.PUBLISHED)
                .createdBy(createdBy)
                .build());
        Long id = page.getId();
        page.updatePath("/" + id);
        kbPageRepository.save(page);
        return id;
    }

    // ═════════════════════════════════════════════════════════════════════
    // ページ作成(createPage) — parentId 越境
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ページ作成(createPage) — parentId 束縛")
    class CreatePageParentId {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/knowledge-base/pages", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody("新ページ", "new-page-1", null, null))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境parentId（他teamのADMINが自teamのURLで他teamのpageIdを親に指定）は404（BOLA・KB_001）")
        void 越境parentIdは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/knowledge-base/pages", teamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("乗っ取りページ", "hijack-page-1", pageAId, null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("KB_001"));
        }

        @Test
        @DisplayName("正当ADMIN・自team内parentIdは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/knowledge-base/pages", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("子ページ", "child-page-1", pageAId, null))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.parentId").value(pageAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ページ作成(createPage) — templateId 越境
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ページ作成(createPage) — templateId 束縛")
    class CreatePageTemplateId {

        @Test
        @DisplayName("越境templateId（他teamのADMINが自teamのURLで他teamのtemplateIdを流用）は404（BOLA・KB_010）")
        void 越境templateIdは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/knowledge-base/pages", teamBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("乗っ取りページ2", "hijack-page-2", null, templateAId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("KB_010"));
        }

        @Test
        @DisplayName("正当ADMIN・自team内templateIdは201・テンプレ本文が反映される")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/knowledge-base/pages", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createBody("テンプレ適用ページ", "template-page-1", null, templateAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.body").value("テンプレ本文A"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ページ移動(movePage) — newParentId 越境
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ページ移動(movePage) — newParentId 束縛")
    class MovePage {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/move",
                            teamAId, pageAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(moveBody(null))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境newParentId（他teamのADMINが自team配下のpageを他teamのpageId配下へ移動）は404（BOLA・KB_001）")
        void 越境newParentIdは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/move",
                            teamBId, pageBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(moveBody(pageAId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("KB_001"));
        }

        @Test
        @DisplayName("正当ADMIN・自team内への移動は204")
        void 正当ADMINは204() throws Exception {
            Long childId = createPage(teamAId, "kbpage-page-a-child", adminAId);

            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/knowledge-base/pages/{pageId}/move",
                            teamAId, childId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(moveBody(pageAId))))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> createBody(String title, String slug, Long parentId, Long templateId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("slug", slug);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        if (templateId != null) {
            body.put("templateId", templateId);
        }
        return body;
    }

    private Map<String, Object> moveBody(Long newParentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newParentId", newParentId);
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
                                + "VALUES (:email, 'KBPAGE認可契約', 'テスト', 'KBPAGE認可契約テスト', 'ACTIVE', "
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
                                + "CONCAT('kbpage-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
