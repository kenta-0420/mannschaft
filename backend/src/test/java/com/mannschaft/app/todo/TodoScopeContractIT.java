package com.mannschaft.app.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.entity.TodoSharedMemoEntryEntity;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.repository.TodoSharedMemoEntryRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave5 — todo 硬化 PR-A（{@link com.mannschaft.app.todo.security.TodoAccessGuard}）
 * の scope 束縛＋membership／owner-or-admin 認可 契約テスト（試練 / red 先行）。
 *
 * <p>真の穴: {@code TodoService} は {@code AccessControlService} を注入すらしておらず、
 * TEAM / ORGANIZATION スコープの todo EP（一覧・詳細・子一覧・更新・ステータス変更・担当者・削除・復元・
 * 一括変更）が membership 認可を一切行っていなかった。Controller が呼ぶ {@code assertTodoScope} は
 * scope 束縛（IDOR 秘匿 404）しか行わず、非メンバーが正しい teamId/orgId を推測して叩くと通ってしまう
 * BOLA/IDOR が成立していた（Team/Org 両側）。本テストは:</p>
 * <ul>
 *   <li>scope 級 EP（listTodos / getGanttTodos / createTodo / bulkChangeStatus）: {@code requireScopeMember}
 *       （非メンバー 403 / メンバー 200・201）</li>
 *   <li>todo 指定 EP（getTodo / getChildTodos / updateTodo / changeStatus / addAssignee / removeAssignee）:
 *       {@code verifyScopeAndMembership}（非メンバー 403 / 他 scope todoId 404 秘匿 / 正当メンバー 200）</li>
 *   <li>削除・復元 EP（deleteTodo / restoreTodo）: {@code verifyScopeAndOwnerOrAdmin}
 *       （作成者 200・ADMIN 200・非作成者の一般メンバー 403・非メンバー 403・他 scope 404）</li>
 *   <li>bulkChangeStatus: 他 scope の todoId を混入させても対象外（scope 絞りで変更されない）</li>
 * </ul>
 * を Team（スラッグ path）／Org（数値 path）の両系統で検証する。
 *
 * <p>金型: {@code TodoCommentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)}
 * + 実 MySQL + 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。seed 列は同 IT を
 * verbatim 写経して整合を保証する。越境 403/404 は Service/Guard のアプリケーション層例外
 * （{@code COMMON_002} → 403 / {@code TODO_010 NOT_FOUND} → 404）として発生するためフィルタ無効でも検証できる。
 * 未認証は {@code SecurityUtils} の {@code COMMON_000} → 401。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("todo scope 束縛＋membership／owner-or-admin 認可契約テスト（試練 Wave5 PR-A）")
class TodoScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMilestoneRepository milestoneRepository;

    @Autowired
    private TodoSharedMemoEntryRepository sharedMemoRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private String teamASlug;   // team todo API はスラッグ受け（resolveTeamId(slug)）。path にはこれを渡す
    private String teamBSlug;
    private Long orgAId;
    private Long orgBId;

    private Long ownerTeamAId;   // team A の TODO 作成者（MEMBER）
    private Long memberTeamAId;  // team A の別 MEMBER（非作成者）
    private Long adminTeamAId;   // team A の ADMIN（user_roles）
    private Long memberTeamBId;  // team B のメンバー（team A に対しては非メンバー＝越境攻撃者）
    private Long ownerOrgAId;    // org A の TODO 作成者（MEMBER）
    private Long memberOrgBId;   // org B のメンバー（org A に対しては非メンバー＝越境攻撃者）
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long todoTeamAId;         // team A の TODO（createdBy = ownerTeamA）
    private Long todoTeamBId;         // team B の TODO（bulk 越境絞りの検証用）
    private Long deletedTodoTeamAId;  // team A の論理削除済み TODO（restore 検証用・createdBy = ownerTeamA）
    private Long todoOrgAId;          // org A の TODO（createdBy = ownerOrgA）

    private Long projectTeamAId;      // team A のプロジェクト（createdBy = ownerTeamA）
    private Long projectTeamBId;      // team B のプロジェクト（他プロジェクトの mid 越境検証用）
    private Long milestoneTeamAId;    // projectTeamA のマイルストーン
    private Long sharedMemoTeamAId;   // todoTeamA の共有メモ（createdBy = ownerTeamA）

    @BeforeEach
    void setUp() {
        // slug は teams/organizations とも @Column(length = 30) のため 30 字以内に収める。
        // base36 圧縮した nanoTime（約12字・英数字）＋ 2字接頭辞で一意化（"ta-"/"tb-"/"oa-"/"ob-" ≤ 30 字）。
        String uniq = Long.toString(System.nanoTime(), 36);
        teamASlug = "ta-" + uniq;
        teamBSlug = "tb-" + uniq;
        String orgASlug = "oa-" + uniq;
        String orgBSlug = "ob-" + uniq;
        teamAId = insertTeam("TODOAUTHZ チームA", teamASlug);
        teamBId = insertTeam("TODOAUTHZ チームB", teamBSlug);
        orgAId = insertOrganization("TODOAUTHZ 組織A", orgASlug);
        orgBId = insertOrganization("TODOAUTHZ 組織B", orgBSlug);

        ownerTeamAId = insertUser("todoauthz-owner-team-a@example.com");
        memberTeamAId = insertUser("todoauthz-member-team-a@example.com");
        adminTeamAId = insertUser("todoauthz-admin-team-a@example.com");
        memberTeamBId = insertUser("todoauthz-member-team-b@example.com");
        ownerOrgAId = insertUser("todoauthz-owner-org-a@example.com");
        memberOrgBId = insertUser("todoauthz-member-org-b@example.com");
        outsiderId = insertUser("todoauthz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, ownerTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, ownerOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        // adminTeamA は user_roles で ADMIN を付与（checkAdminOrAbove が見るのは user_roles 由来のロール）。
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        // outsiderId はどこにも所属させない。

        TodoEntity todoTeamA = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(teamAId)
                .title("TODOAUTHZ チームTODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(ownerTeamAId)
                .build());
        todoTeamAId = todoTeamA.getId();

        TodoEntity todoTeamB = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(teamBId)
                .title("TODOAUTHZ チームB TODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(memberTeamBId)
                .build());
        todoTeamBId = todoTeamB.getId();

        TodoEntity deletedTodoTeamA = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(teamAId)
                .title("TODOAUTHZ 削除済みチームTODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(ownerTeamAId)
                .build());
        deletedTodoTeamA.softDelete();
        deletedTodoTeamA = todoRepository.save(deletedTodoTeamA);
        deletedTodoTeamAId = deletedTodoTeamA.getId();

        TodoEntity todoOrgA = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.ORGANIZATION)
                .scopeId(orgAId)
                .title("TODOAUTHZ 組織TODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(ownerOrgAId)
                .build());
        todoOrgAId = todoOrgA.getId();

        ProjectEntity projectTeamA = projectRepository.save(ProjectEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(teamAId)
                .title("TODOAUTHZ プロジェクトA")
                .status(com.mannschaft.app.todo.ProjectStatus.ACTIVE)
                .visibility(com.mannschaft.app.todo.ProjectVisibility.SHARED)
                .createdBy(ownerTeamAId)
                .build());
        projectTeamAId = projectTeamA.getId();

        ProjectEntity projectTeamB = projectRepository.save(ProjectEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(teamBId)
                .title("TODOAUTHZ プロジェクトB")
                .status(com.mannschaft.app.todo.ProjectStatus.ACTIVE)
                .visibility(com.mannschaft.app.todo.ProjectVisibility.SHARED)
                .createdBy(memberTeamBId)
                .build());
        projectTeamBId = projectTeamB.getId();

        ProjectMilestoneEntity milestoneTeamA = milestoneRepository.save(ProjectMilestoneEntity.builder()
                .projectId(projectTeamAId)
                .title("TODOAUTHZ マイルストーンA")
                .sortOrder((short) 0)
                .isCompleted(false)
                .build());
        milestoneTeamAId = milestoneTeamA.getId();

        TodoSharedMemoEntryEntity sharedMemoTeamA = sharedMemoRepository.save(TodoSharedMemoEntryEntity.builder()
                .todoId(todoTeamAId)
                .userId(ownerTeamAId)
                .memo("TODOAUTHZ 共有メモA")
                .build());
        sharedMemoTeamAId = sharedMemoTeamA.getId();

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. getTodo（verifyScopeAndMembership・最重点: 現状生 IDOR）— Team + Org
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /todos/{id}（詳細取得・scope 束縛＋membership）")
    class GetTodo {

        @Test
        @DisplayName("Team: 未認証は401")
        void team_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}", teamASlug, todoTeamAId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Team: 非メンバー(outsider)は403")
        void team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Team: 別scopeメンバー(teamBメンバーがteamAのURL)は403")
        void team_別scopeメンバーは403() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Team: 正当メンバーは200")
        void team_正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}", teamASlug, todoTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Team: BOLA 他scope todoId を自チームURLで指定→404秘匿（生IDOR根治）")
        void team_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}", teamBSlug, todoTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Org: 非メンバーは403")
        void org_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos/{id}", orgAId, todoOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Org: 別scopeメンバー(orgBメンバー)は403")
        void org_別scopeメンバーは403() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos/{id}", orgAId, todoOrgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Org: 正当メンバーは200")
        void org_正当メンバーは200() throws Exception {
            setAuth(ownerOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos/{id}", orgAId, todoOrgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Org: BOLA orgBメンバーが自組織URLでorgAのtodoId→404秘匿")
        void org_BOLAは404秘匿() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos/{id}", orgBId, todoOrgAId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. getChildTodos（verifyScopeAndMembership・GET）— Team
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /todos/{id}/children（子一覧・scope 束縛＋membership）")
    class GetChildTodos {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/children", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA 他scope todoId→404秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/children", teamBSlug, todoTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/children", teamASlug, todoTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. changeStatus / addAssignee（verifyScopeAndMembership・変更系）— Team + Org
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 変更系 EP（changeStatus / addAssignee・scope 束縛＋membership）")
    class WriteWithMembership {

        @Test
        @DisplayName("changeStatus Team: 非メンバーは403")
        void changeStatus_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/status", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "IN_PROGRESS"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("changeStatus Team: BOLA 他scope todoId→404秘匿")
        void changeStatus_team_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/status", teamBSlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "IN_PROGRESS"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("changeStatus Team: 正当メンバーは200")
        void changeStatus_team_正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/status", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "IN_PROGRESS"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("changeStatus Org: 非メンバーは403")
        void changeStatus_org_非メンバーは403() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/todos/{id}/status", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "IN_PROGRESS"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("changeStatus Org: 正当メンバーは200")
        void changeStatus_org_正当メンバーは200() throws Exception {
            setAuth(ownerOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/todos/{id}/status", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "IN_PROGRESS"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("addAssignee Team: 非メンバーは403")
        void addAssignee_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/assignees", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userId", memberTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("addAssignee Team: BOLA 他scope todoId→404秘匿")
        void addAssignee_team_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/assignees", teamBSlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userId", memberTeamBId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("addAssignee Team: 正当メンバーは201")
        void addAssignee_team_正当メンバーは201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/assignees", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("userId", memberTeamAId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. scope 級 EP（requireScopeMember: listTodos / createTodo / gantt）— Team + Org
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. scope 級 EP（listTodos / createTodo / gantt・membership のみ）")
    class ScopeLevel {

        @Test
        @DisplayName("listTodos Team: 非メンバーは403")
        void listTodos_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos", teamASlug))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("listTodos Team: 正当メンバーは200")
        void listTodos_team_正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos", teamASlug))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("listTodos Org: 非メンバーは403 / 正当メンバーは200")
        void listTodos_org() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos", orgAId))
                    .andExpect(status().isForbidden());
            setAuth(ownerOrgAId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("createTodo Team: 非メンバーは403")
        void createTodo_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "越境作成"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("createTodo Team: 正当メンバーは201")
        void createTodo_team_正当メンバーは201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "正当な作成"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("createTodo Org: 非メンバーは403")
        void createTodo_org_非メンバーは403() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "越境作成"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("gantt Team: 非メンバーは403")
        void gantt_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/gantt", teamASlug)
                            .param("from", "2999-01-01").param("to", "2999-12-31"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("gantt Team: 正当メンバーは200（0件でも404化せず空で返る）")
        void gantt_team_正当メンバーは空でも200() throws Exception {
            setAuth(memberTeamAId);
            // 未来日レンジ → 該当 TODO なし。requireScopeMember は todo 件数に依存しないため 200。
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/gantt", teamASlug)
                            .param("from", "2999-01-01").param("to", "2999-12-31"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 削除・復元（verifyScopeAndOwnerOrAdmin: 作成者 or ADMIN）— Team 全マトリクス + Org
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. deleteTodo / restoreTodo（作成者 or ADMIN）")
    class OwnerOrAdmin {

        @Test
        @DisplayName("deleteTodo Team: 作成者本人は204")
        void delete_team_作成者は204() throws Exception {
            setAuth(ownerTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}", teamASlug, todoTeamAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("deleteTodo Team: ADMIN(非作成者)は204")
        void delete_team_ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}", teamASlug, todoTeamAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("deleteTodo Team: 非作成者の一般メンバーは403")
        void delete_team_非作成者メンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("deleteTodo Team: 非メンバーは403")
        void delete_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("deleteTodo Team: BOLA 他scope todoId→404秘匿")
        void delete_team_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}", teamBSlug, todoTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("deleteTodo Org: 作成者本人は204")
        void delete_org_作成者は204() throws Exception {
            setAuth(ownerOrgAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/todos/{id}", orgAId, todoOrgAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("restoreTodo Team: 作成者本人は200")
        void restore_team_作成者は200() throws Exception {
            setAuth(ownerTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/restore", teamASlug, deletedTodoTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("restoreTodo Team: 非作成者の一般メンバーは403")
        void restore_team_非作成者メンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/restore", teamASlug, deletedTodoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("restoreTodo Team: 非メンバーは403")
        void restore_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/restore", teamASlug, deletedTodoTeamAId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. bulkChangeStatus（requireScopeMember ＋ 内部 scope 絞り・越境 BOLA 根治）— Team
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. PATCH /todos/bulk-status（membership ＋ scope 絞り）")
    class BulkChangeStatus {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/bulk-status", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("todoIds", List.of(todoTeamAId), "status", "IN_PROGRESS"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("越境 id 混入: 自 scope の todo のみ変更され、他 scope の todo は変更されない")
        void 越境id混入は対象外() throws Exception {
            setAuth(ownerTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/bulk-status", teamASlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "todoIds", List.of(todoTeamAId, todoTeamBId),
                                    "status", "IN_PROGRESS"))))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            // 自 scope（teamA）の todo は IN_PROGRESS に変更される。
            assertThat(todoRepository.findById(todoTeamAId).orElseThrow().getStatus())
                    .isEqualTo(TodoStatus.IN_PROGRESS);
            // 他 scope（teamB）の todo は scope 絞りで対象外 → 変更されない（OPEN のまま）。
            assertThat(todoRepository.findById(todoTeamBId).orElseThrow().getStatus())
                    .isEqualTo(TodoStatus.OPEN);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. ErrorCode ステータス写像是正ロットA — 追加登録した 404 秘匿の契約固定
    //    （TODO_007/012/015/016/050/060。TODO_016 は TodoCommentScopeContractIT で既に固定済み）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. ロットA追加404: milestone / assignee / shared-memo / personal-memo")
    class LotAAdditionalNotFound {

        @Test
        @DisplayName("MILESTONE_NOT_FOUND(TODO_007): 他プロジェクトのmidを指定した削除は404")
        void milestone_他プロジェクトのmidは404() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/projects/{id}/milestones/{mid}",
                            teamBSlug, projectTeamBId, milestoneTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("ASSIGNEE_NOT_FOUND(TODO_015): 割り当てられていないuserIdの削除は404")
        void assignee_未割当ユーザーの削除は404() throws Exception {
            setAuth(ownerTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/assignees/{userId}",
                            teamASlug, todoTeamAId, memberTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("SHARED_MEMO_NOT_FOUND(TODO_050): 存在しないmemoIdの削除は404")
        void sharedMemo_他todoのmemoIdは404() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamBSlug, todoTeamBId, sharedMemoTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("PERSONAL_MEMO_NOT_FOUND(TODO_060): 未作成の個人メモ取得は404")
        void personalMemo_未作成は404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamASlug, todoTeamAId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（seed 列は TodoCommentScopeContractIT を verbatim 写経）
    // ═════════════════════════════════════════════════════════════════════

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
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
                                + "VALUES (:email, 'TODOAUTHZ', 'テスト', 'TODOAUTHZ テスト', 'ACTIVE', "
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
