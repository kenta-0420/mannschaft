package com.mannschaft.app.onboarding;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7: onboarding（社内オンボーディング）ドメインの
 * {@code OnboardingMeController}（本人用）API 契約テスト（試練）。
 *
 * <p>正本: {@code OnboardingProgressService#getByIdForMember}/{@code completeStepByMember}。
 * {@code progressId} から進捗エンティティを取得し、所有者が操作者本人であることを要求する
 * （BOLA 対策）。本人以外は {@code ONBOARDING_003}（404・存在秘匿）で拒否する。</p>
 *
 * <p>金型: {@code ProxyVoteAuthzContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + ネイティブ SQL フィクスチャ）。onboarding は
 * テンプレート/進捗の作成フローが長いため、ネイティブ SQL で直接シードする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("onboarding ドメイン 本人用 API 契約テスト（認可根治 Wave7）")
class OnboardingMeScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long ownerId;
    private Long otherId;
    private Long templateId;
    private Long stepId;
    private Long progressId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("ONB認可契約チームA");
        ownerId = insertUser("onb-authz-owner@example.com");
        otherId = insertUser("onb-authz-other@example.com");

        templateId = insertTemplate(teamAId, ownerId);
        stepId = insertStep(templateId, "MANUAL");
        progressId = insertProgress(templateId, ownerId, teamAId);

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("進捗詳細取得(getById)")
    class GetById {

        @Test
        @DisplayName("他人の進捗詳細取得は404(BOLA存在秘匿)")
        void 他人の進捗詳細取得は404() throws Exception {
            setAuthentication(otherId);
            mockMvc.perform(get("/api/v1/onboarding/progresses/me/{progressId}", progressId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ONBOARDING_003"));
        }

        @Test
        @DisplayName("本人の進捗詳細取得は200")
        void 本人の進捗詳細取得は200() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/onboarding/progresses/me/{progressId}", progressId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(progressId));
        }
    }

    @Nested
    @DisplayName("ステップ完了(completeStep)")
    class CompleteStep {

        @Test
        @DisplayName("他人の進捗へのステップ完了は404(BOLA存在秘匿)")
        void 他人の進捗のステップ完了は404() throws Exception {
            setAuthentication(otherId);
            mockMvc.perform(post("/api/v1/onboarding/progresses/me/{progressId}/steps/{stepId}/complete",
                            progressId, stepId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ONBOARDING_003"));
        }

        @Test
        @DisplayName("本人のステップ完了は200")
        void 本人のステップ完了は200() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/onboarding/progresses/me/{progressId}/steps/{stepId}/complete",
                            progressId, stepId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isCompleted").value(true));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
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
                                + "VALUES (:email, 'ONB契約', 'テスト', 'ONB契約テスト', 'ACTIVE', "
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
                                + "CONCAT('onb-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * {@code onboarding_templates} を1件シードする。
     *
     * <p>test profile は {@code ddl-auto: create} で {@code OnboardingTemplateEntity} の
     * {@code @Column(nullable=false)} からスキーマを生成するため、Flyway のデフォルト値は
     * 効かない。{@code @Column(nullable=false)} の全列（scope_type/scope_id/name/status/
     * is_order_enforced/is_admin_notified_on_complete/is_timeline_posted_on_complete/
     * created_by）と {@code @Version} 列（version）を機械的に列挙して INSERT に含める。</p>
     */
    private Long insertTemplate(Long teamId, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO onboarding_templates ("
                                + "scope_type, scope_id, name, status, is_order_enforced, "
                                + "is_admin_notified_on_complete, is_timeline_posted_on_complete, "
                                + "created_by, version, created_at, updated_at) "
                                + "VALUES ('TEAM', :scopeId, :name, 'ACTIVE', 0, "
                                + "1, 0, "
                                + ":createdBy, 0, NOW(), NOW())")
                .setParameter("scopeId", teamId)
                .setParameter("name", "認可契約テストテンプレート " + System.nanoTime())
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM onboarding_templates").getSingleResult()).longValue();
    }

    private Long insertStep(Long templateId, String stepType) {
        em.createNativeQuery(
                        "INSERT INTO onboarding_template_steps ("
                                + "template_id, title, step_type, sort_order, created_at, updated_at) "
                                + "VALUES (:templateId, 'ステップ1', :stepType, 1, NOW(), NOW())")
                .setParameter("templateId", templateId)
                .setParameter("stepType", stepType)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM onboarding_template_steps").getSingleResult()).longValue();
    }

    private Long insertProgress(Long templateId, Long userId, Long scopeId) {
        em.createNativeQuery(
                        "INSERT INTO onboarding_progresses ("
                                + "template_id, user_id, scope_type, scope_id, status, total_steps, completed_steps, "
                                + "started_at, created_at, updated_at) "
                                + "VALUES (:templateId, :userId, 'TEAM', :scopeId, 'IN_PROGRESS', 1, 0, "
                                + "NOW(), NOW(), NOW())")
                .setParameter("templateId", templateId)
                .setParameter("userId", userId)
                .setParameter("scopeId", scopeId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM onboarding_progresses").getSingleResult()).longValue();
    }
}
