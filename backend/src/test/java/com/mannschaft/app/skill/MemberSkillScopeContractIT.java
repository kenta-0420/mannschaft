package com.mannschaft.app.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 3 バッチB11 — skill（メンバースキル・資格管理）ドメイン
 * scope 突合 API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code MemberSkillService} の getSkill/updateSkill/deleteSkill/verifySkill が
 * 「本人 or ADMIN」しか見ておらず、資格（{@code member_skills}）の所属スコープ
 * （scopeType/scopeId）が path の teamId と一致するかを突合していなかった。そのため
 * teamB の ADMIN が {@code teams/{teamB}/skills/{teamAのskillId}} を叩くと、
 * {@code isAdmin(userRole)} が teamB 内では true になり、他チームの資格を
 * 閲覧・更新・削除・承認できてしまう BOLA（Broken Object Level Authorization）が成立していた。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 非権限（同一チーム内で本人でも ADMIN でもない）/ 越境 BOLA（別チーム ADMIN が
 * 他チームの skillId を path teamId 配下と偽って叩く）/ 正当（本人 or 同チーム ADMIN）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("skill（メンバースキル）ドメイン scope突合 認可契約テスト（試練・Wave3-B11）")
class MemberSkillScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberSkillRepository memberSkillRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;  // TEAM A の ADMIN（正当）
    private Long adminTeamBId;  // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId; // TEAM A の非 ADMIN メンバー（資格の所有者本人）
    private Long otherMemberTeamAId; // TEAM A の非 ADMIN メンバー（本人でも ADMIN でもない第三者）

    private Long skillTeamAId; // TEAM A 配下・memberTeamAId 所有の資格（PENDING_REVIEW）
    private Long skillVersion;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SKILLAUTHZ チームA");
        teamBId = insertTeam("SKILLAUTHZ チームB");

        adminTeamAId = insertUser("skillauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("skillauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("skillauthz-member-team-a@example.com");
        otherMemberTeamAId = insertUser("skillauthz-other-member-team-a@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership/getRoleName（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（EquipmentScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        MemberSkillEntity skillTeamA = memberSkillRepository.save(MemberSkillEntity.builder()
                .userId(memberTeamAId)
                .scopeType("TEAM")
                .scopeId(teamAId)
                .name("SKILLAUTHZ 普通救命講習")
                .status(SkillStatus.PENDING_REVIEW)
                .build());
        skillTeamAId = skillTeamA.getId();
        skillVersion = skillTeamA.getVersion();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /teams/{teamId}/skills/{id}（詳細取得）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/skills/{id}（詳細取得）")
    class GetSkill {

        @Test
        @DisplayName("非権限: 同一チームの本人でもADMINでもない第三者は400（SKILL_003）")
        void 非権限は400() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/skills/{id}", teamAId, skillTeamAId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINがteamAの資格IDをteamB配下と偽って叩くと400（scope不一致で秘匿）")
        void 越境BOLAは400() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/skills/{id}", teamBId, skillTeamAId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("正当: 本人は200")
        void 本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/skills/{id}", teamAId, skillTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当: 同チームADMINは200")
        void 同チームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/skills/{id}", teamAId, skillTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PUT /teams/{teamId}/skills/{id}（更新）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PUT /teams/{teamId}/skills/{id}（更新）")
    class UpdateSkill {

        @Test
        @DisplayName("非権限: 第三者は400（SKILL_003）")
        void 非権限は400() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/skills/{id}", teamAId, skillTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINは400（scope不一致で秘匿・他チーム資格の改変を阻止）")
        void 越境BOLAは400() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/skills/{id}", teamBId, skillTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("正当: 本人は200")
        void 本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/skills/{id}", teamAId, skillTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "SKILLAUTHZ 更新後資格名");
            body.put("version", skillVersion);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. DELETE /teams/{teamId}/skills/{id}（論理削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. DELETE /teams/{teamId}/skills/{id}（論理削除）")
    class DeleteSkill {

        @Test
        @DisplayName("非権限: 第三者は400（SKILL_003）")
        void 非権限は400() throws Exception {
            setAuth(otherMemberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/skills/{id}", teamAId, skillTeamAId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINは400（scope不一致で秘匿・他チーム資格の削除を阻止）")
        void 越境BOLAは400() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/skills/{id}", teamBId, skillTeamAId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("正当: 同チームADMINは204")
        void 同チームADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/skills/{id}", teamAId, skillTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PATCH /teams/{teamId}/skills/{id}/verify（承認・ADMINのみ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PATCH /teams/{teamId}/skills/{id}/verify（承認）")
    class VerifySkill {

        @Test
        @DisplayName("非ADMIN: 本人であってもADMINでなければ403（COMMON_002・Controller入口ガード）")
        void 非ADMINは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/skills/{id}/verify", teamAId, skillTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境BOLA: teamBのADMINは自チームADMINチェックは通るがscope不一致で400（他チーム資格の承認を阻止）")
        void 越境BOLAは400() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/skills/{id}/verify", teamBId, skillTeamAId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("正当: 同チームADMINは200")
        void 同チームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/skills/{id}/verify", teamAId, skillTeamAId))
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
                                + "VALUES (:email, 'SKILLAUTHZ', 'テスト', 'SKILLAUTHZ テスト', 'ACTIVE', "
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
