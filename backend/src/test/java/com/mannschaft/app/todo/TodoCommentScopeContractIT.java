package com.mannschaft.app.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.todo.entity.TodoCommentEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoCommentRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 早馬（BOLA 閉塞）— todo コメント（{@link com.mannschaft.app.todo.service.TodoCommentService}）
 * の path scope 束縛＋membership 認可 契約テスト（試練 / red 先行）。
 *
 * <p>真の穴: {@code TeamTodoController} / {@code OrgTodoController} のコメント
 * EP（listComments / addComment / updateComment）は Controller が受けた path の
 * teamId / organizationId を Service へ渡さず、Service は {@code verifyTodoExists}
 * で存在確認しか行っていなかった。このため TODO の内部 id を知る任意の認証ユーザーが
 * 所属外チーム/組織のコメントを閲覧(read)・投稿(write)できる BOLA/IDOR が成立していた
 * （Team/Org 両側）。兄弟 {@code addAssignee} は {@code assertTodoScope}（scope 束縛）は
 * 呼ぶが membership を検証しないため、scope 束縛のみでは非メンバーが正しい teamId/orgId を
 * 推測して叩くと通ってしまう。本テストは <b>scope 束縛（404 秘匿）＋membership（403）</b>の
 * 両方を検証する。</p>
 *
 * <p>金型: {@code ChartScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)}
 * + 実 MySQL + 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。
 * Spring Security フィルタは無効化するが、越境 403/404 は Service のアプリケーション層
 * 例外（{@code COMMON_002} → 403 / {@code TODO_010 NOT_FOUND} → 404）として発生するため
 * フィルタ無効でも検証できる。未認証は {@code SecurityUtils} の {@code COMMON_000} → 401。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("todo コメント scope 束縛＋membership 認可契約テスト（試練）")
class TodoCommentScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private TodoCommentRepository todoCommentRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private String teamASlug;   // team todo API はスラッグ受け（resolveTeamId(slug)）。path にはこれを渡す
    private String teamBSlug;
    private Long orgAId;
    private Long orgBId;

    private Long memberTeamAId;  // team A のメンバー（正当）
    private Long memberTeamBId;  // team B のメンバー（team A に対しては非メンバー＝越境攻撃者）
    private Long memberOrgAId;   // org A のメンバー（正当）
    private Long memberOrgBId;   // org B のメンバー（org A に対しては非メンバー＝越境攻撃者）
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long todoTeamAId;    // team A の TODO
    private Long todoOrgAId;     // org A の TODO
    private Long commentTeamAId; // team A の TODO に memberTeamA が投稿したコメント
    private Long commentOrgAId;  // org A の TODO に memberOrgA が投稿したコメント

    @BeforeEach
    void setUp() {
        // slug は teams/organizations とも @Column(length = 30) のため 30 字以内に収める。
        // base36 圧縮した nanoTime（約12字・英数字）＋ 2字接頭辞で一意化（"ta-"/"tb-"/"oa-"/"ob-" ≤ 30 字）。
        String uniq = Long.toString(System.nanoTime(), 36);
        teamASlug = "ta-" + uniq;
        teamBSlug = "tb-" + uniq;
        String orgASlug = "oa-" + uniq;
        String orgBSlug = "ob-" + uniq;
        teamAId = insertTeam("TODOCMTAUTHZ チームA", teamASlug);
        teamBId = insertTeam("TODOCMTAUTHZ チームB", teamBSlug);
        orgAId = insertOrganization("TODOCMTAUTHZ 組織A", orgASlug);
        orgBId = insertOrganization("TODOCMTAUTHZ 組織B", orgBSlug);

        memberTeamAId = insertUser("todocmtauthz-member-team-a@example.com");
        memberTeamBId = insertUser("todocmtauthz-member-team-b@example.com");
        memberOrgAId = insertUser("todocmtauthz-member-org-a@example.com");
        memberOrgBId = insertUser("todocmtauthz-member-org-b@example.com");
        outsiderId = insertUser("todocmtauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        TodoEntity todoTeamA = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(teamAId)
                .title("TODOCMTAUTHZ チームTODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(memberTeamAId)
                .build());
        todoTeamAId = todoTeamA.getId();

        TodoEntity todoOrgA = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.ORGANIZATION)
                .scopeId(orgAId)
                .title("TODOCMTAUTHZ 組織TODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(memberOrgAId)
                .build());
        todoOrgAId = todoOrgA.getId();

        TodoCommentEntity commentTeamA = todoCommentRepository.save(TodoCommentEntity.builder()
                .todoId(todoTeamAId)
                .userId(memberTeamAId)
                .body("TODOCMTAUTHZ チームコメント")
                .build());
        commentTeamAId = commentTeamA.getId();

        TodoCommentEntity commentOrgA = todoCommentRepository.save(TodoCommentEntity.builder()
                .todoId(todoOrgAId)
                .userId(memberOrgAId)
                .body("TODOCMTAUTHZ 組織コメント")
                .build());
        commentOrgAId = commentOrgA.getId();

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. チーム: コメント一覧（閲覧系: scope 束縛＋membership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/todos/{id}/comments（チームコメント一覧）")
    class TeamListComments {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/comments", teamASlug, todoTeamAId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバー(outsider)は403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/comments", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scopeメンバー(teamBメンバーがteamAのURL)は403")
        void 別scopeメンバーは403() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/comments", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/comments", teamASlug, todoTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("BOLA: teamBメンバーが自チームURLでteamAのtodoIdを指定→404秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/comments", teamBSlug, todoTeamAId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. チーム: コメント追加（変更系: scope 束縛＋membership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/todos/{id}/comments（チームコメント追加）")
    class TeamAddComment {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/comments", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境投稿")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scopeメンバー(teamBメンバー)は403")
        void 別scopeメンバーは403() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/comments", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境投稿")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは201")
        void 正当メンバーは201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/comments", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("正当な投稿")))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("BOLA: teamBメンバーが自チームURLでteamAのtodoIdを指定→404秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/comments", teamBSlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境投稿")))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. チーム: コメント編集（本人＋scope 束縛＋membership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PUT /teams/{teamId}/todos/{id}/comments/{commentId}（チームコメント編集）")
    class TeamUpdateComment {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/comments/{commentId}",
                            teamASlug, todoTeamAId, commentTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境編集")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scopeメンバー(teamBメンバー)は403")
        void 別scopeメンバーは403() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/comments/{commentId}",
                            teamASlug, todoTeamAId, commentTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境編集")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamBメンバーが自チームURLでteamAのtodoIdを指定→404秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/comments/{commentId}",
                            teamBSlug, todoTeamAId, commentTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境編集")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当本人は200")
        void 正当本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/comments/{commentId}",
                            teamASlug, todoTeamAId, commentTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("更新後コメント")))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 組織: コメント一覧・追加・編集（TEAM と同型・ORGANIZATION スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 組織 /organizations/{orgId}/todos/{id}/comments（一覧・追加・編集）")
    class OrgComments {

        @Test
        @DisplayName("一覧: 非メンバーは403")
        void 一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos/{id}/comments", orgAId, todoOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一覧: 別scopeメンバー(orgBメンバー)は403")
        void 一覧_別scopeメンバーは403() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos/{id}/comments", orgAId, todoOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一覧: 正当メンバーは200")
        void 一覧_正当メンバーは200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos/{id}/comments", orgAId, todoOrgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("追加: 非メンバーは403")
        void 追加_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos/{id}/comments", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境投稿")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("追加: 正当メンバーは201")
        void 追加_正当メンバーは201() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos/{id}/comments", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("正当な投稿")))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("追加BOLA: orgBメンバーが自組織URLでorgAのtodoIdを指定→404秘匿")
        void 追加_BOLAは404秘匿() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos/{id}/comments", orgBId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境投稿")))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("編集: 非メンバーは403")
        void 編集_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/todos/{id}/comments/{commentId}",
                            orgAId, todoOrgAId, commentOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("越境編集")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("編集: 正当本人は200")
        void 編集_正当本人は200() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(put("/api/v1/organizations/{orgId}/todos/{id}/comments/{commentId}",
                            orgAId, todoOrgAId, commentOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commentBody("更新後コメント")))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private String commentBody(String body) throws Exception {
        return objectMapper.writeValueAsString(Map.of("body", body));
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
                                + "VALUES (:email, 'TODOCMTAUTHZ', 'テスト', 'TODOCMTAUTHZ テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
