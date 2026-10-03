package com.mannschaft.app.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import com.mannschaft.app.skill.repository.SkillCategoryRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.Assertions;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * skill（スキルカテゴリ管理）ドメイン scope 突合 API 契約テスト。
 *
 * <p>正本: {@code SkillCategoryService} の updateCategory/deleteCategory は「不在」（SKILL_001・404）
 * と「越境（所有スコープ不一致）」（SKILL_003）の応答が割れており、存在オラクルになっていた。
 * 本テストは越境も404に揃った（SKILL_003 が404に是正された）ことを契約として固定する。
 * SkillCategoryController の updateCategory/deleteCategory はいずれも
 * {@code accessControlService.checkAdminOrAbove(userId, teamId, "TEAM")} で path teamId に対する
 * ADMIN 権限を確認するため、そこは通過するが対象カテゴリの所有スコープ（category.scopeType/scopeId）
 * が path teamId と一致しない場合に SKILL_003 を投げる（{@code SkillCategoryService} 内部）。</p>
 *
 * <p>金型: {@code MemberSkillScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("skill（スキルカテゴリ）ドメイン scope突合 認可契約テスト")
class SkillCategoryScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SkillCategoryRepository skillCategoryRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long NON_EXISTENT_CATEGORY_ID = 999_999_999L;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId; // TEAM A の ADMIN（正当）
    private Long adminTeamBId; // TEAM B の ADMIN（別 scope の越境攻撃者）

    private Long categoryTeamAId; // TEAM A 配下のカテゴリ

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SKILLCATAUTHZ チームA");
        teamBId = insertTeam("SKILLCATAUTHZ チームB");

        adminTeamAId = insertUser("skillcatauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("skillcatauthz-admin-team-b@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);

        SkillCategoryEntity categoryTeamA = skillCategoryRepository.save(SkillCategoryEntity.builder()
                .scopeType("TEAM")
                .scopeId(teamAId)
                .name("SKILLCATAUTHZ カテゴリ")
                .createdBy(adminTeamAId)
                .build());
        categoryTeamAId = categoryTeamA.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. PUT /teams/{teamId}/skill-categories/{id}（更新）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. PUT /teams/{teamId}/skill-categories/{id}（更新）")
    class UpdateCategory {

        @Test
        @DisplayName("不在: 存在しないIDは404（SKILL_001）")
        void 不在は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/skill-categories/{id}", teamAId, NON_EXISTENT_CATEGORY_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINがteamAのカテゴリIDをteamB配下と偽って叩くと404（scope不一致で存在秘匿）")
        void 越境BOLAは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/skill-categories/{id}", teamBId, categoryTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当: 同チームADMINは200")
        void 同チームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/skill-categories/{id}", teamAId, categoryTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("不在と越境の応答ステータスが一致する")
        void 不在と越境で同一ステータス() throws Exception {
            setAuth(adminTeamAId);
            int notFoundStatus = mockMvc.perform(
                            put("/api/v1/teams/{teamId}/skill-categories/{id}", teamAId, NON_EXISTENT_CATEGORY_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateBody())))
                    .andReturn().getResponse().getStatus();

            setAuth(adminTeamBId);
            int crossScopeStatus = mockMvc.perform(
                            put("/api/v1/teams/{teamId}/skill-categories/{id}", teamBId, categoryTeamAId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateBody())))
                    .andReturn().getResponse().getStatus();

            Assertions.assertThat(crossScopeStatus).isEqualTo(notFoundStatus);
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "SKILLCATAUTHZ 更新後カテゴリ名");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. DELETE /teams/{teamId}/skill-categories/{id}（論理削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. DELETE /teams/{teamId}/skill-categories/{id}（論理削除）")
    class DeleteCategory {

        @Test
        @DisplayName("不在: 存在しないIDは404（SKILL_001）")
        void 不在は404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/skill-categories/{id}", teamAId, NON_EXISTENT_CATEGORY_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINは404（scope不一致で存在秘匿・他チームカテゴリの削除を阻止）")
        void 越境BOLAは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/skill-categories/{id}", teamBId, categoryTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当: 同チームADMINは204")
        void 同チームADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/skill-categories/{id}", teamAId, categoryTeamAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("不在と越境の応答ステータスが一致する")
        void 不在と越境で同一ステータス() throws Exception {
            setAuth(adminTeamAId);
            int notFoundStatus = mockMvc.perform(
                            delete("/api/v1/teams/{teamId}/skill-categories/{id}", teamAId, NON_EXISTENT_CATEGORY_ID))
                    .andReturn().getResponse().getStatus();

            setAuth(adminTeamBId);
            int crossScopeStatus = mockMvc.perform(
                            delete("/api/v1/teams/{teamId}/skill-categories/{id}", teamBId, categoryTeamAId))
                    .andReturn().getResponse().getStatus();

            Assertions.assertThat(crossScopeStatus).isEqualTo(notFoundStatus);
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
                                + "VALUES (:email, 'SKILLCATAUTHZ', 'テスト', 'SKILLCATAUTHZ テスト', 'ACTIVE', "
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
