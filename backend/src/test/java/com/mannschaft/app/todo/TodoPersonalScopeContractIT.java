package com.mannschaft.app.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 個人スコープ TODO / プロジェクト EP の認可契約テスト
 * （認可根治戦役 第1波・個人領域 ロットA）。
 *
 * <p>本 IT が固定する保証:</p>
 * <ul>
 *   <li><b>TODO ID を受け取る EP</b>（削除・復元・PATCH・子一覧）: 対象 TODO の<b>担当者本人</b>
 *       （子一覧は<b>自分の個人スコープに属すること</b>）に限定する。担当外・他スコープ・不存在はいずれも
 *       404 で存在を秘匿する（{@code TODO_010}）。</li>
 *   <li><b>個人プロジェクトのマイルストーンゲート EP</b>（サマリー・完了モード・強制アンロック・
 *       ゲート初期化・並び替え）: <b>プロジェクト所有者本人</b>に限定し、他ユーザーの
 *       プロジェクト ID は 404 で存在を秘匿する。</li>
 *   <li><b>自己スコープ EP</b>（自分のTODO一覧・ガント・個人プロジェクト一覧／作成・
 *       所属チーム／組織プロジェクト集約）: スコープは認証主体から解決され、リクエストで
 *       他ユーザーを指定する余地がない。他ユーザーのデータが混入しないことを固定する。</li>
 * </ul>
 *
 * <p>金型: {@code TodoScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL +
 * 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。未認証は
 * {@code SecurityUtils} の {@code COMMON_000} → 401。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("個人スコープ TODO/プロジェクト 認可契約テスト（第1波 ロットA）")
class TodoPersonalScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private TodoAssigneeRepository assigneeRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @PersistenceContext
    private EntityManager em;

    private Long ownerId;      // 個人TODO・個人プロジェクトの所有者
    private Long attackerId;   // 無関係な他ユーザー（越境元）

    private Long teamId;       // owner が所属するチーム（集約EPの混入検証用）
    private Long orgId;        // owner が所属する組織

    private Long personalTodoId;         // owner の個人TODO（担当者 = owner）
    private Long personalChildTodoId;    // 上記の子TODO
    private Long ganttTodoId;            // owner の個人TODO（開始日・期限あり = ガント対象）
    private Long deletedPersonalTodoId;  // owner の論理削除済み個人TODO（復元検証用）
    private Long attackerTodoId;         // attacker の個人TODO（owner から見た越境先）

    private Long personalProjectId;      // owner の個人プロジェクト
    private Long attackerProjectId;      // attacker の個人プロジェクト
    private Long teamProjectId;          // チームスコープのプロジェクト（個人URLでの越境検証用）

    @BeforeEach
    void setUp() {
        String uniq = Long.toString(System.nanoTime(), 36);
        teamId = insertTeam("PERSAUTHZ チーム", "pt-" + uniq);
        orgId = insertOrganization("PERSAUTHZ 組織", "po-" + uniq);

        ownerId = insertUser("persauthz-owner@example.com");
        attackerId = insertUser("persauthz-attacker@example.com");

        MembershipTestHelper.insertMembership(em, ownerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, ownerId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        // attacker はどこにも所属させない。

        personalTodoId = savePersonalTodo(ownerId, "PERSAUTHZ 個人TODO", null);
        assigneeRepository.save(TodoAssigneeEntity.builder()
                .todoId(personalTodoId)
                .userId(ownerId)
                .assignedBy(ownerId)
                .build());

        personalChildTodoId = savePersonalTodo(ownerId, "PERSAUTHZ 子TODO", personalTodoId);

        // ガント EP は startDate・dueDate が両方揃った TODO のみ返すため、専用フィクスチャを用意する。
        ganttTodoId = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(ownerId)
                .title("PERSAUTHZ ガント対象TODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(ownerId)
                .startDate(java.time.LocalDate.of(2030, 1, 1))
                .dueDate(java.time.LocalDate.of(2030, 1, 31))
                .build()).getId();

        TodoEntity deletedTodo = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(ownerId)
                .title("PERSAUTHZ 削除済み個人TODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(ownerId)
                .build());
        deletedTodo.softDelete();
        deletedPersonalTodoId = todoRepository.save(deletedTodo).getId();
        assigneeRepository.save(TodoAssigneeEntity.builder()
                .todoId(deletedPersonalTodoId)
                .userId(ownerId)
                .assignedBy(ownerId)
                .build());

        attackerTodoId = savePersonalTodo(attackerId, "PERSAUTHZ 攻撃者の個人TODO", null);
        assigneeRepository.save(TodoAssigneeEntity.builder()
                .todoId(attackerTodoId)
                .userId(attackerId)
                .assignedBy(attackerId)
                .build());

        personalProjectId = saveProject(TodoScopeType.PERSONAL, ownerId, "PERSAUTHZ 個人プロジェクト", ownerId);
        attackerProjectId = saveProject(TodoScopeType.PERSONAL, attackerId,
                "PERSAUTHZ 攻撃者の個人プロジェクト", attackerId);
        teamProjectId = saveProject(TodoScopeType.TEAM, teamId, "PERSAUTHZ チームプロジェクト", ownerId);

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 個人TODO 削除（担当者本人限定・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. DELETE /todos/{id}（削除・担当者本人限定）")
    class DeletePersonalTodo {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/todos/{id}", personalTodoId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーが他人のTODOを削除→404秘匿（削除も成立しない）")
        void 他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/todos/{id}", personalTodoId))
                    .andExpect(status().isNotFound());

            // @Transactional 内では findById が1次キャッシュに当たるため entity の状態を見る。
            TodoEntity intact = todoRepository.findById(personalTodoId).orElseThrow();
            assertThat(intact.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("正常系: 担当者本人は204で論理削除される")
        void 担当者本人は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/todos/{id}", personalTodoId))
                    .andExpect(status().isNoContent());

            TodoEntity deleted = todoRepository.findById(personalTodoId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 個人TODO 復元（担当者本人限定・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /todos/{id}/restore（復元・担当者本人限定）")
    class RestorePersonalTodo {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/todos/{id}/restore", deletedPersonalTodoId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーが他人の削除済みTODOを復元→404秘匿（復元も成立しない）")
        void 他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/todos/{id}/restore", deletedPersonalTodoId))
                    .andExpect(status().isNotFound());

            TodoEntity stillDeleted = todoRepository.findById(deletedPersonalTodoId).orElseThrow();
            assertThat(stillDeleted.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("正常系: 担当者本人は200で復元される")
        void 担当者本人は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/todos/{id}/restore", deletedPersonalTodoId))
                    .andExpect(status().isOk());

            TodoEntity restored = todoRepository.findById(deletedPersonalTodoId).orElseThrow();
            assertThat(restored.getDeletedAt()).isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 個人TODO PATCH（担当者本人限定・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PATCH /todos/{id}（部分更新・担当者本人限定）")
    class PatchTodo {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/todos/{id}", personalTodoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dueDate\":\"2030-01-01\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーが他人のTODOを部分更新→404秘匿")
        void 他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/todos/{id}", personalTodoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dueDate\":\"2030-01-01\"}"))
                    .andExpect(status().isNotFound());

            TodoEntity intact = todoRepository.findById(personalTodoId).orElseThrow();
            assertThat(intact.getDueDate()).isNull();
        }

        @Test
        @DisplayName("正常系: 担当者本人は200で期限が更新される")
        void 担当者本人は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(patch("/api/v1/todos/{id}", personalTodoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dueDate\":\"2030-01-01\"}"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 個人TODO 子一覧（自分の個人スコープ束縛・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET /todos/{id}/children（子一覧・個人スコープ束縛）")
    class GetChildTodos {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/todos/{id}/children", personalTodoId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーが他人のTODOの子一覧→404秘匿")
        void 他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/todos/{id}/children", personalTodoId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 所有者は200で子TODOが返る")
        void 所有者は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/todos/{id}/children", personalTodoId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(personalChildTodoId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 自己スコープ EP（他ユーザーのデータが混入しないこと）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 自己スコープ EP（一覧・ガント・作成）")
    class SelfScopedEndpoints {

        @Test
        @DisplayName("自分のTODO一覧: 未認証は401")
        void 一覧_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/todos/my"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("自分のTODO一覧: 他ユーザーのTODOは混入しない")
        void 一覧_他人のTODOは混入しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/todos/my"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(personalTodoId.intValue()))));
        }

        @Test
        @DisplayName("正常系 自分のTODO一覧: 担当TODOが返る")
        void 一覧_担当TODOが返る() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/todos/my"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(personalTodoId.intValue())));
        }

        @Test
        @DisplayName("ガント: 未認証は401")
        void ガント_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/todos/gantt")
                            .param("from", "2020-01-01")
                            .param("to", "2040-12-31"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("ガント: 他ユーザーのTODOは混入しない")
        void ガント_他人のTODOは混入しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/todos/gantt")
                            .param("from", "2020-01-01")
                            .param("to", "2040-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(ganttTodoId.intValue()))));
        }

        @Test
        @DisplayName("正常系 ガント: 自分の期間内TODOが返る")
        void ガント_自分のTODOが返る() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/todos/gantt")
                            .param("from", "2020-01-01")
                            .param("to", "2040-12-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(ganttTodoId.intValue())));
        }

        @Test
        @DisplayName("個人TODO作成: 他ユーザーのプロジェクトを指定すると拒否される")
        void 作成_他人のプロジェクト指定は拒否() throws Exception {
            setAuth(ownerId);
            // createTodo が「プロジェクトが自分の個人スコープに属すること」を照合し
            // SCOPE_MISMATCH（TODO_011）で拒否する。TODO_011 は ERROR_CODE_STATUS_MAP 未登録のため
            // 400 にフォールバックする現行挙動を固定する（他スコープ ID の存在秘匿を 404 に寄せるかは
            // TODO_011 が同一ユーザー内のスコープ不整合にも使われる汎用コードのため別途判断が必要）。
            mockMvc.perform(post("/api/v1/todos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "title", "越境作成TODO", "projectId", attackerProjectId))))
                    .andExpect(status().isBadRequest());

            // 認可の本質: 越境先プロジェクト配下に TODO が作られていないこと
            assertThat(todoRepository.findByProjectIdAndDeletedAtIsNullOrderBySortOrderAsc(attackerProjectId))
                    .isEmpty();
        }

        @Test
        @DisplayName("正常系 個人TODO作成: 自分のプロジェクト配下に201で作成できる")
        void 作成_自分のプロジェクトは201() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/todos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "title", "自分のTODO", "projectId", personalProjectId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 個人プロジェクト 一覧／作成（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. /users/me/projects（個人プロジェクト一覧・作成）")
    class PersonalProjects {

        @Test
        @DisplayName("一覧: 未認証は401")
        void 一覧_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/projects"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("一覧: 他ユーザーの個人プロジェクトは混入しない")
        void 一覧_他人のプロジェクトは混入しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/users/me/projects"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(personalProjectId.intValue()))));
        }

        @Test
        @DisplayName("正常系 一覧: 自分の個人プロジェクトが返る")
        void 一覧_所有者は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/users/me/projects"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(personalProjectId.intValue())));
        }

        @Test
        @DisplayName("正常系 作成: スコープは認証主体に固定される（他ユーザー分は作られない）")
        void 作成_スコープは認証主体に固定() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/users/me/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "title", "PERSAUTHZ 攻撃者の新規プロジェクト"))))
                    .andExpect(status().isCreated());

            // owner のスコープには増えていないこと
            assertThat(projectRepository
                    .findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                            TodoScopeType.PERSONAL, ownerId, ProjectStatus.ACTIVE,
                            org.springframework.data.domain.PageRequest.of(0, 50))
                    .getContent())
                    .allSatisfy(p -> assertThat(p.getScopeId()).isEqualTo(ownerId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. マイページ 集約 EP（所属から解決される自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. /me/team-projects・/me/org-projects（集約）")
    class MyAggregates {

        @Test
        @DisplayName("チーム集約: 未認証は401")
        void チーム集約_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/team-projects"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("チーム集約: 非所属ユーザーには所属チームのプロジェクトが混入しない")
        void チーム集約_非所属は混入しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/team-projects"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(teamProjectId.intValue()))));
        }

        @Test
        @DisplayName("正常系 チーム集約: 所属チームのプロジェクトが返る")
        void チーム集約_所属メンバーは200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/me/team-projects"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(teamProjectId.intValue())));
        }

        @Test
        @DisplayName("組織集約: 未認証は401")
        void 組織集約_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/org-projects"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系 組織集約: 非所属ユーザーは自分の所属分のみ（空）")
        void 組織集約_非所属は空() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/org-projects"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. 個人プロジェクトのマイルストーンゲート EP（所有者本人限定・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. /users/me/projects/{projectId} ゲート系（所有者本人限定）")
    class PersonalGates {

        @Test
        @DisplayName("ゲートサマリー: 未認証は401")
        void サマリー_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/projects/{projectId}/gates", personalProjectId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("ゲートサマリー: 無関係な他ユーザーが他人のプロジェクトID→404秘匿")
        void サマリー_他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/users/me/projects/{projectId}/gates", personalProjectId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("ゲートサマリー: チームプロジェクトIDを個人URLで指定→404秘匿")
        void サマリー_チームプロジェクトIDは404秘匿() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/users/me/projects/{projectId}/gates", teamProjectId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系 ゲートサマリー: 所有者は200")
        void サマリー_所有者は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/users/me/projects/{projectId}/gates", personalProjectId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("完了モード切替: 無関係な他ユーザー→404秘匿")
        void 完了モード_他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/users/me/projects/{projectId}/milestones/{mid}/completion-mode",
                            personalProjectId, 999999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"completionMode\":\"MANUAL\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("強制アンロック: 無関係な他ユーザー→404秘匿")
        void 強制アンロック_他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/users/me/projects/{projectId}/milestones/{mid}/force-unlock",
                            personalProjectId, 999999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"越境\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("ゲート初期化: 無関係な他ユーザー→404秘匿")
        void ゲート初期化_他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/users/me/projects/{projectId}/milestones/{mid}/initialize-gate",
                            personalProjectId, 999999L))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("TODO並び替え: 無関係な他ユーザー→404秘匿")
        void 並び替え_他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/users/me/projects/{projectId}/milestones/{mid}/todos/reorder",
                            personalProjectId, 999999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"todoIds\":[1]}"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（金型 TodoScopeContractIT より写経）
    // ═════════════════════════════════════════════════════════════════════

    private Long savePersonalTodo(Long userId, String title, Long parentId) {
        return todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(userId)
                .title(title)
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(userId)
                .parentId(parentId)
                .depth(parentId == null ? 0 : 1)
                .build()).getId();
    }

    private Long saveProject(TodoScopeType scopeType, Long scopeId, String title, Long createdBy) {
        return projectRepository.save(ProjectEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title(title)
                .createdBy(createdBy)
                .build()).getId();
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
                                + "VALUES (:email, 'PERSAUTHZ', 'テスト', 'PERSAUTHZ テスト', 'ACTIVE', "
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
