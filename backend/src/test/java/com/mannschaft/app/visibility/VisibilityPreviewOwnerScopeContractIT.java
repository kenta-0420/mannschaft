package com.mannschaft.app.visibility;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
 * {@code ownerUserId} を起点にオーナーの主所属チームを解決するため、任意の他ユーザー ID を
 * {@code ownerUserId} に詐称することで、当該ユーザーの関係グラフ（主所属チーム/フレンドチーム）を
 * 本人になりすまさず覗ける IDOR だった
 * （システムプリセットテンプレートは全ユーザーがアクセス可能なため攻撃者も自由に呼べる）。</p>
 *
 * <p><b>是正後</b>: owner は常に {@code SecurityUtils.getCurrentUserId()}
 * （呼び出し本人）に固定される。本テストは以下を実証する:</p>
 * <ul>
 *   <li>本人が自分のテンプレートプレビューを行う分には従来どおり正しく動作する（ベースライン）。</li>
 *   <li>攻撃者がリクエストボディ/クエリパラメータに他人の {@code ownerUserId} を詐称して混入させても、
 *       DTO / パラメータから除去済のため無視され、<b>攻撃者自身のコンテキスト</b>で評価される
 *       （被害者の関係グラフは一切列挙できない）。</li>
 * </ul>
 *
 * <p><b>2 つの EP のメカニズム差（アサート設計上の重要事項）</b>:</p>
 * <ul>
 *   <li>{@code resolveMemberUserIds}（resolved-members）: {@code TEAM_FRIEND_OF} +
 *       {@code @USER_PRIMARY_TEAM} の解決先は「オーナーの<b>主所属チームそのもの</b>のメンバー集合」
 *       （{@code findUserIdsByScope("TEAM", ownerPrimaryTeam)}）。つまり owner が誰かで
 *       返る集合が変わる。よって「owner=victim なら victim の主所属チームメンバー、owner=attacker なら
 *       attacker の主所属チームメンバー」という差でサーバー固定を検証できる。</li>
 *   <li>{@code canView}（evaluate）: {@code evaluateTeamFriendOf} が owner の主所属チームの
 *       <b>フレンドチーム</b>に viewer が属するかを判定する。owner が誰かで targetTeam が変わる。</li>
 * </ul>
 *
 * <p>アサートは順序・具体 ID の決め打ちを避け、集合の {@code contains}/{@code doesNotContain}/
 * 非一致で頑健に検証する（seed の自動採番 ID に依存しない）。</p>
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
    /** 被害者の主所属チームとフレンド関係にあるチーム（evaluate のフレンド探索用）。 */
    private Long teamVictimFriendId;
    /** 攻撃者の主所属チーム。誰ともフレンド関係を持たない。 */
    private Long teamAttackerId;

    private Long victimId;
    /** teamVictim の別メンバー。被害者コンテキストの resolved-members にのみ現れるべき識別子。 */
    private Long victimTeammateId;
    private Long attackerId;
    /** teamAttacker の別メンバー。攻撃者コンテキストの resolved-members に現れる識別子。 */
    private Long attackerTeammateId;
    /** teamVictimFriend のメンバー。evaluate のフレンド探索で被害者視点でのみ true になる。 */
    private Long victimFriendMemberId;

    /** システムプリセットテンプレート（TEAM_FRIEND_OF + @USER_PRIMARY_TEAM ルール）。 */
    private Long presetTemplateId;

    @BeforeEach
    void setUp() {
        teamVictimId = insertTeam("VIS認可契約被害者チーム");
        teamVictimFriendId = insertTeam("VIS認可契約被害者フレンドチーム");
        teamAttackerId = insertTeam("VIS認可契約攻撃者チーム");

        victimId = insertUser("vis-authz-victim@example.com");
        victimTeammateId = insertUser("vis-authz-victim-teammate@example.com");
        attackerId = insertUser("vis-authz-attacker@example.com");
        attackerTeammateId = insertUser("vis-authz-attacker-teammate@example.com");
        victimFriendMemberId = insertUser("vis-authz-victim-friend-member@example.com");

        // 主所属チームの解決は user_roles (team_id IS NOT NULL) を見る（VisibilityTemplateEvaluator）。
        // victim / victimTeammate は teamVictim、attacker / attackerTeammate は teamAttacker に配属し、
        // 両チームのメンバー集合が互いに素になるようにする（詐称検証の doesNotContain を意味あるものにする）。
        MembershipTestHelper.insertUserRole(em, victimId, "MEMBER", teamVictimId, null);
        MembershipTestHelper.insertUserRole(em, victimTeammateId, "MEMBER", teamVictimId, null);
        MembershipTestHelper.insertUserRole(em, attackerId, "MEMBER", teamAttackerId, null);
        MembershipTestHelper.insertUserRole(em, attackerTeammateId, "MEMBER", teamAttackerId, null);
        MembershipTestHelper.insertUserRole(em, victimFriendMemberId, "MEMBER", teamVictimFriendId, null);

        // 被害者チーム ⇔ 被害者フレンドチーム のみフレンド関係を成立させる（攻撃者チームは孤立）。
        insertTeamFriend(teamVictimId, teamVictimFriendId);

        presetTemplateId = insertPresetTemplate("VIS認可契約プリセット（主所属チーム基点）");
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
        @DisplayName("被害者本人の resolved-members は被害者コンテキスト（主所属チームのメンバー）を返す")
        void 被害者本人のresolvedMembersは被害者コンテキストを返す() throws Exception {
            Set<Long> resolved = fetchResolvedUserIds(victimId, null);

            // 被害者の主所属チーム（teamVictim）のメンバーが返る。順序・件数の決め打ちはせず contains で頑健に。
            assertThat(resolved)
                    .as("被害者本人のプレビューは自分の主所属チームメンバーを含む")
                    .contains(victimTeammateId)
                    // 攻撃者チームのメンバーは混ざらない（コンテキスト分離の確認）。
                    .doesNotContain(attackerTeammateId);
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
        @DisplayName("resolved-members に被害者の ownerUserId をクエリパラメータで混入させても無視され、"
                + "攻撃者自身のコンテキストで評価される（被害者の主所属チームメンバーは列挙されない）")
        void resolvedMembersのownerUserId詐称は無視される() throws Exception {
            // ① ベースライン: 被害者本人が叩いたときに返る集合（＝被害者の関係グラフ）。
            Set<Long> victimContext = fetchResolvedUserIds(victimId, null);
            // ② 攻撃者が被害者の ownerUserId を詐称して叩いた結果。
            Set<Long> attackerSpoofed = fetchResolvedUserIds(attackerId, victimId);

            assertThat(attackerSpoofed)
                    .as("詐称結果は攻撃者自身のコンテキスト（自分の主所属チームメンバー）である")
                    .contains(attackerTeammateId);
            assertThat(attackerSpoofed)
                    .as("詐称しても被害者の主所属チームメンバー（関係グラフ）は一切列挙されない")
                    .doesNotContain(victimTeammateId)
                    .doesNotContain(victimId);
            assertThat(attackerSpoofed)
                    .as("詐称結果は被害者本人のプレビュー結果と一致しない（サーバー固定が効いている）")
                    .isNotEqualTo(victimContext);
        }

        @Test
        @DisplayName("evaluate に被害者の ownerUserId をリクエストボディで混入させても無視され、"
                + "攻撃者自身のコンテキストで評価される（被害者フレンドチームメンバーへの閲覧可否は false）")
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

    /**
     * 指定ユーザーとして {@code GET /resolved-members} を叩き、返却された userIds を Set で返す。
     *
     * @param callerId              認証主体（サーバーが owner として採用すべき ID）
     * @param spoofedOwnerUserIdOrNull 詐称のため混入させる ownerUserId クエリパラメータ（不要なら null）
     */
    private Set<Long> fetchResolvedUserIds(Long callerId, Long spoofedOwnerUserIdOrNull) throws Exception {
        setAuthentication(callerId);
        MockHttpServletRequestBuilder req =
                get("/api/v1/visibility-templates/{id}/resolved-members", presetTemplateId);
        if (spoofedOwnerUserIdOrNull != null) {
            // 是正後の Controller には @RequestParam ownerUserId が無いため、未知パラメータとして無視される。
            req.param("ownerUserId", spoofedOwnerUserIdOrNull.toString());
        }
        String json = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode userIds = objectMapper.readTree(json).path("data").path("userIds");
        Set<Long> result = new HashSet<>();
        userIds.forEach(node -> result.add(node.asLong()));
        return result;
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
