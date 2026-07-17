package com.mannschaft.app.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.template.entity.ModuleDefinitionEntity;
import com.mannschaft.app.template.entity.TeamTemplateEntity;
import com.mannschaft.app.template.entity.TemplateModuleEntity;
import com.mannschaft.app.template.repository.ModuleDefinitionRepository;
import com.mannschaft.app.template.repository.TeamEnabledModuleRepository;
import com.mannschaft.app.template.repository.TeamTemplateRepository;
import com.mannschaft.app.template.repository.TemplateModuleRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB11 — template（チームモジュール管理）ドメイン
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code TeamModuleController} の {@code getTeamModules}（GET 一覧）・
 * {@code toggleTeamModule}（PATCH トグル）・{@code applyTemplate}（PUT テンプレート一括適用）
 * に認可チェックが一切敷設されておらず、認証済みであれば任意ユーザーが slug を知るだけで
 * 他チームのモジュールを ON/OFF・テンプレート一括適用できる状態だった
 * （{@code OrganizationModuleController} は同種操作が ADMIN ガード済みで対称性を欠いていた）。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 非メンバー/非ADMINメンバー（outsider・memberTeamA）/ 別 scope ADMIN
 * （teamB の ADMIN が teamA の slug URL を叩く越境）/ 正当 ADMIN（または一覧は正当メンバー）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("template（チームモジュール管理）ドメイン 認可契約テスト（試練・Wave3-B11）")
class TeamModuleScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ModuleDefinitionRepository moduleDefinitionRepository;

    @Autowired
    private TeamEnabledModuleRepository teamEnabledModuleRepository;

    @Autowired
    private TeamTemplateRepository teamTemplateRepository;

    @Autowired
    private TemplateModuleRepository templateModuleRepository;

    @PersistenceContext
    private EntityManager em;

    private String teamASlug;
    private String teamBSlug;

    private Long adminTeamAId;  // TEAM A の ADMIN（正当）
    private Long adminTeamBId;  // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId; // TEAM A の非 ADMIN メンバー
    private Long outsiderId;    // どこにも所属しない非メンバー

    private Long moduleId;
    private Long templateId;

    @BeforeEach
    void setUp() {
        Long teamAId = insertTeam("TPLAUTHZ チームA", "tplauthz-team-a-" + System.nanoTime());
        Long teamBId = insertTeam("TPLAUTHZ チームB", "tplauthz-team-b-" + System.nanoTime());
        teamASlug = selectTeamSlug(teamAId);
        teamBSlug = selectTeamSlug(teamBId);

        adminTeamAId = insertUser("tplauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("tplauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("tplauthz-member-team-a@example.com");
        outsiderId = insertUser("tplauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（EquipmentScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        ModuleDefinitionEntity module = moduleDefinitionRepository.save(ModuleDefinitionEntity.builder()
                .name("TPLAUTHZ 選択式モジュール")
                .slug("tplauthz-module-" + System.nanoTime())
                .moduleType(ModuleDefinitionEntity.ModuleType.OPTIONAL)
                .moduleNumber(1)
                .requiresPaidPlan(false)
                .isActive(true)
                .build());
        moduleId = module.getId();

        TeamTemplateEntity template = teamTemplateRepository.save(TeamTemplateEntity.builder()
                .name("TPLAUTHZ テンプレート")
                .slug("tplauthz-template-" + System.nanoTime())
                .isActive(true)
                .build());
        templateId = template.getId();

        templateModuleRepository.save(TemplateModuleEntity.builder()
                .templateId(templateId)
                .moduleId(moduleId)
                .build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{slug}/modules（一覧: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{slug}/modules（一覧）")
    class GetTeamModules {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{slug}/modules", teamASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINがteamAのslugを叩くと403")
        void 越境BOLAは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{slug}/modules", teamASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当: 同チームメンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/modules", teamASlug))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PATCH /teams/{slug}/modules/{moduleId}/toggle（トグル: ADMINのみ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PATCH /teams/{slug}/modules/{moduleId}/toggle（トグル）")
    class ToggleTeamModule {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/modules/{moduleId}/toggle", teamASlug, moduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(toggleBody(true))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINがteamAのslugを叩くと403（無認可トグルの根治）")
        void 越境BOLAは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/modules/{moduleId}/toggle", teamASlug, moduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(toggleBody(true))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当: 同チームADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/modules/{moduleId}/toggle", teamASlug, moduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(toggleBody(true))))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> toggleBody(boolean enabled) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("moduleId", moduleId);
            body.put("enabled", enabled);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PUT /teams/{slug}/modules/template（テンプレート一括適用: ADMINのみ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PUT /teams/{slug}/modules/template（テンプレート適用）")
    class ApplyTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{slug}/modules/template", teamASlug)
                            .param("templateId", String.valueOf(templateId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINがteamAのslugを叩くと403（無認可一括適用の根治）")
        void 越境BOLAは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{slug}/modules/template", teamASlug)
                            .param("templateId", String.valueOf(templateId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当: 同チームADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{slug}/modules/template", teamASlug)
                            .param("templateId", String.valueOf(templateId)))
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
                                + "VALUES (:email, 'TPLAUTHZ', 'テスト', 'TPLAUTHZ テスト', 'ACTIVE', "
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

    private String selectTeamSlug(Long teamId) {
        return (String) em.createNativeQuery("SELECT slug FROM teams WHERE id = :id")
                .setParameter("id", teamId)
                .getSingleResult();
    }
}
