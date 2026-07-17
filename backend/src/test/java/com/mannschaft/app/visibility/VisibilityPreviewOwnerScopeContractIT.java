package com.mannschaft.app.visibility;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave4: visibility テンプレートプレビュー（evaluate / resolved-members）の
 * ownerUserId サーバー固定化に関する API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave4 可視性の独自迂回是正①節）・{@code VisibilityTemplateController}
 * （evaluate / getResolvedMembers）・{@code VisibilityTemplateEvaluator}
 * （{@code canView} / {@code resolveMemberUserIds} の {@code ownerUserId} 引数）。</p>
 *
 * <p><b>是正前の脆弱性</b>: 両 EP は client 供給の {@code ownerUserId}
 * （リクエストボディ / クエリパラメータ）をそのまま {@code VisibilityTemplateEvaluator} に渡していた。
 * {@code TEAM_FRIEND_OF} ルールの {@code @USER_PRIMARY_TEAM} プレースホルダは
 * {@code ownerUserId} を起点にオーナーの所属チーム（→フレンドチーム→メンバー一覧）を解決するため、
 * 任意の他ユーザー ID を {@code ownerUserId} に詐称することで、当該ユーザーの所属チーム/
 * フレンドチームメンバー一覧を本人になりすまさず列挙できる IDOR だった
 * （システムプリセットテンプレートは全ユーザーがアクセス可能なため攻撃者も自由に呼べる）。</p>
 *
 * <p><b>是正後</b>: owner は常に {@code SecurityUtils.getCurrentUserId()}
 * （呼び出し本人）に固定される。本テストは以下を実証する:</p>
 * <ul>
 *   <li>本人が自分のテンプレートプレビューを行う分には従来どおり正しく動作する（ベースライン）。</li>
 *   <li>攻撃者がリクエストボディ/クエリパラメータに他人の {@code ownerUserId} を詐称して混入させても、
 *       DTO からフィールドが除去されているため無視され、攻撃者自身のコンテキストで評価される
 *       （他人の所属チーム/フレンドチームメンバーは一切列挙できない）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("visibility テンプレートプレビュー ownerUserId サーバー固定化 API 契約テスト（認可根治 Wave4）")
class VisibilityPreviewOwnerScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** 被害者の主所属チーム。 */
    private Long teamVictimId;
    /** 被害者の主所属チームとフレンド関係にあるチーム（被害者からのみ列挙されるべき）。 */
    private Long teamVictimFriendId;
    /** 攻撃者の主所属チーム。誰ともフレンド関係を持たない。 */
    private Long teamAttackerId;

    private Long victimId;
    private Long attackerId;
    /** teamVictimFriend のメンバー。被害者視点でのみ resolved-members に現れるべき。 */
    private Long victimFriendMemberId;

    /** システムプリセットテンプレート（TEAM_FRIEND_OF + @USER_PRIMARY_TEAM ルール）。 */
    private Long presetTemplateId;

    @BeforeEach
    void setUp() {
        teamVictimId = insertTeam("VIS認可契約被害者チーム");
        teamVictimFriendId = insertTeam("VIS認可契約被害者フレンドチーム");
        teamAttackerId = insertTeam("VIS認可契約攻撃者チーム");

        victimId = insertUser("vis-authz-victim@example.com");
        attackerId = insertUser("vis-authz-attacker@example.com");
        victimFriendMemberId = insertUser("vis-authz-victim-friend-member@example.com");

        // 主所属チームの解決は user_roles (team_id IS NOT NULL) を見る（VisibilityTemplateEvaluator）。
        MembershipTestHelper.insertUserRole(em, victimId, "MEMBER", teamVictimId, null);
        MembershipTestHelper.insertUserRole(em, attackerId, "MEMBER", teamAttackerId, null);
        MembershipTestHelper.insertUserRole(em, victimFriendMemberId, "MEMBER", teamVictimFriendId, null);

        // 被害者チーム ⇔ 被害者フレンドチーム のみフレンド関係を成立させる（攻撃者チームは孤立）。
        insertTeamFriend(teamVictimId, teamVictimFriendId);

        presetTemplateId = insertPresetTemplate("VIS認可契約プリセット（フレンドチーム全員）");
        insertTeamFriendOfPrimaryTeamRule(presetTemplateId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ベースライン: 本人が自分のテンプレートプレビューを行う分には従来どおり動作する
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("本人によるプレビュー（ベースライン）")
    class OwnPreview {

        @Test
        @DisplayName("被害者本人の resolved-members はフレンドチームメンバーを含む")
        void 被害者本人のresolvedMembersはフレンドチームメンバーを含む() throws Exception {
            setAuthentication(victimId);
            mockMvc.perform(get("/api/v1/visibility-templates/{id}/resolved-members", presetTemplateId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalUsers").value(1))
                    .andExpect(jsonPath("$.data.userIds[0]").value(victimFriendMemberId));
        }

        @Test
        @DisplayName("被害者本人の evaluate はフレンドチームメンバーに対して true")
        void 被害者本人のevaluateはフレンドチームメンバーに対してtrue() throws Exception {
            setAuthentication(victimId);
            mockMvc.perform(post("/api/v1/visibility-templates/{id}/evaluate", presetTemplateId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    evaluateBody(victimFriendMemberId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.canView").value(true));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 是正確認: ownerUserId 詐称は無視され、攻撃者自身のコンテキストで評価される
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("攻撃者による ownerUserId 詐称（是正確認）")
    class OwnerSpoofAttempt {

        @Test
        @DisplayName("resolved-members に他人の ownerUserId をクエリパラメータで混入させても無視される"
                + "（攻撃者自身のコンテキストで評価され、被害者のフレンドチームメンバーは列挙されない）")
        void resolvedMembersのownerUserId詐称は無視される() throws Exception {
            setAuthentication(attackerId);
            mockMvc.perform(get("/api/v1/visibility-templates/{id}/resolved-members?ownerUserId={victim}",
                            presetTemplateId, victimId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalUsers").value(0))
                    .andExpect(jsonPath("$.data.userIds").isEmpty());
        }

        @Test
        @DisplayName("evaluate に他人の ownerUserId をリクエストボディで混入させても無視される"
                + "（攻撃者自身のコンテキストで評価され、被害者のフレンドチームメンバーへの閲覧可否は false）")
        void evaluateのownerUserId詐称は無視される() throws Exception {
            setAuthentication(attackerId);
            Map<String, Object> spoofedBody = new LinkedHashMap<>();
            spoofedBody.put("targetUserId", victimFriendMemberId);
            // 旧 DTO に存在した ownerUserId を生 JSON で混入させる（DTO から除去済のため無視される想定）。
            spoofedBody.put("ownerUserId", victimId);

            mockMvc.perform(post("/api/v1/visibility-templates/{id}/evaluate", presetTemplateId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(spoofedBody)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.canView").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> evaluateBody(Long targetUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetUserId", targetUserId);
        return body;
    }

    /** visibility_templates へシステムプリセットを 1 行 INSERT する（owner_user_id は NULL）。 */
    private Long insertPresetTemplate(String name) {
        em.createNativeQuery(
                        "INSERT INTO visibility_templates "
                                + "(owner_user_id, name, description, icon_emoji, is_system_preset, preset_key, "
                                + "created_at, updated_at) "
                                + "VALUES (NULL, :name, NULL, NULL, 1, "
                                + "CONCAT('vis-authz-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM visibility_templates WHERE name = :name")
                        .setParameter("name", name)
                        .getSingleResult()).longValue();
    }

    /** visibility_template_rules へ TEAM_FRIEND_OF + @USER_PRIMARY_TEAM ルールを 1 行 INSERT する。 */
    private void insertTeamFriendOfPrimaryTeamRule(Long templateId) {
        em.createNativeQuery(
                        "INSERT INTO visibility_template_rules "
                                + "(template_id, rule_type, rule_target_id, rule_target_text, sort_order, created_at) "
                                + "VALUES (:templateId, 'TEAM_FRIEND_OF', NULL, '@USER_PRIMARY_TEAM', 0, NOW())")
                .setParameter("templateId", templateId)
                .executeUpdate();
    }

    /**
     * team_friends へフレンド関係を 1 行 INSERT する（teamAId &lt; teamBId に正規化）。
     * a_follow_id / b_follow_id はクロスドメイン FK を持たないため、監査用のダミー値でよい。
     */
    private void insertTeamFriend(Long teamId1, Long teamId2) {
        long a = Math.min(teamId1, teamId2);
        long b = Math.max(teamId1, teamId2);
        em.createNativeQuery(
                        "INSERT INTO team_friends "
                                + "(team_a_id, team_b_id, established_at, a_follow_id, b_follow_id, is_public, "
                                + "created_at, updated_at) "
                                + "VALUES (:a, :b, NOW(), 1, 2, 0, NOW(), NOW())")
                .setParameter("a", a)
                .setParameter("b", b)
                .executeUpdate();
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
                                + "VALUES (:email, 'VIS契約', 'テスト', 'VIS契約テスト', 'ACTIVE', "
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
                                + "CONCAT('vis-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
