package com.mannschaft.app.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.entity.TodoPersonalMemoEntity;
import com.mannschaft.app.todo.entity.TodoSharedMemoEntryEntity;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoPersonalMemoRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave5 — todo 硬化 PR-B（署名拡張を伴う生 IDOR 系 EP）の
 * scope 束縛＋membership 認可 契約テスト（試練 / red 先行）。
 *
 * <p>PR-A（{@link TodoScopeContractIT}）が list/get/update/status/delete/restore/assignee/bulk/gantt/create を
 * 硬化したのに対し、本 PR-B は <b>進捗率・進捗モード・共有メモ・個人メモ・スケジュール連携</b> の各 EP を対象とする。
 * これらは従来 {@code findTodoOrThrow}/{@code verifyTodoExists}/owner 照合しか行わず、scope 束縛も membership も
 * 無い生 IDOR/BOLA が成立していた。硬化方針:</p>
 * <ul>
 *   <li><b>共有メモ / 個人メモ / スケジュール連携</b>: {@code TodoAccessGuard#verifyScopeAndMembership} を
 *       各 Service public 入口の先頭で呼ぶよう署名拡張（{@code scopeType/scopeId/userId} 追加）。</li>
 *   <li><b>進捗率 / 進捗モード</b>: {@code setProgressRate} は {@code ActionMemoService} からも呼ばれる
 *       <b>共有メソッド</b>のため、ガードは共有メソッドに埋めず public 入口（Controller）に敷く
 *       （{@code feedback_authz_gate_on_public_entry_not_shared_method}）。</li>
 *   <li>memo destructive（update/delete）は既存の owner / owner-or-admin 照合を維持し、
 *       その手前に scope 束縛＋membership を足す（非メンバーは owner 照合前に 403 で弾かれる）。</li>
 * </ul>
 * Team（スラッグ path）／Org（数値 path）の両系統で、非メンバー 403 / 他 scope todoId 404 秘匿 /
 * 正当メンバー 200(201/204) を検証する。
 *
 * <p>金型: {@link TodoScopeContractIT}（PR-A・main）の setUp を verbatim 写経（seed 列・slug 生成・
 * {@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext + {@code @EnabledIf}）。
 * 越境 403/404 は Service/Guard のアプリケーション層例外（{@code COMMON_002} → 403 /
 * {@code TODO_010 NOT_FOUND} → 404）として発生するためフィルタ無効でも検証できる。未認証は
 * {@code SecurityUtils} の {@code COMMON_000} → 401。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("todo scope 束縛＋membership 認可契約テスト（試練 Wave5 PR-B: 進捗/メモ/連携）")
class TodoScopePartBContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private TodoSharedMemoEntryRepository sharedMemoRepository;

    @Autowired
    private TodoPersonalMemoRepository personalMemoRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private TodoAssigneeRepository assigneeRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private String teamASlug;   // team todo API はスラッグ受け（resolveTeamId(slug)）。path にはこれを渡す
    private String teamBSlug;
    private Long orgAId;
    private Long orgBId;

    private Long ownerTeamAId;   // team A の TODO 作成者（MEMBER）
    private Long memberTeamAId;  // team A の別 MEMBER（メモ所有者）
    private Long adminTeamAId;   // team A の MEMBER かつ user_roles ADMIN（共有メモ ADMIN 削除の検証用）
    private Long memberTeamBId;  // team B のメンバー（team A に対しては非メンバー＝越境攻撃者）
    private Long ownerOrgAId;    // org A の TODO 作成者（MEMBER）
    private Long memberOrgBId;   // org B のメンバー（org A に対しては非メンバー＝越境攻撃者）
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long todoTeamAId;         // team A の TODO（createdBy = ownerTeamA・progressManual=true）
    private Long todoOrgAId;          // org A の TODO（createdBy = ownerOrgA・progressManual=true）
    private Long todoPersonalId;      // PERSONAL TODO（所有者 = memberTeamA / scopeId = memberTeamAId）
    private Long sharedMemoTeamAId;   // todoTeamA 上の共有メモ（投稿者 = memberTeamA）
    private Long personalMemoTeamAId; // todoTeamA 上の個人メモ（本人 = memberTeamA）
    private Long scheduleTeamAId;     // team A スコープの未連携スケジュール（link 正常系用）

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

        ownerTeamAId = insertUser("todoauthzb-owner-team-a@example.com");
        memberTeamAId = insertUser("todoauthzb-member-team-a@example.com");
        adminTeamAId = insertUser("todoauthzb-admin-team-a@example.com");
        memberTeamBId = insertUser("todoauthzb-member-team-b@example.com");
        ownerOrgAId = insertUser("todoauthzb-owner-org-a@example.com");
        memberOrgBId = insertUser("todoauthzb-member-org-b@example.com");
        outsiderId = insertUser("todoauthzb-outsider@example.com");

        MembershipTestHelper.insertMembership(em, ownerTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // adminTeamA は membership(MEMBER)＝ガードの membership 通過用 ＋ user_roles ADMIN＝isAdminOrAbove 用の二本立て。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, ownerOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        TodoEntity todoTeamA = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(teamAId)
                .title("TODOAUTHZ チームTODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .progressManual(true)   // setProgressRate（手動モード必須）の正常系のため手動モードで作る
                .createdBy(ownerTeamAId)
                .build());
        todoTeamAId = todoTeamA.getId();

        TodoEntity todoOrgA = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.ORGANIZATION)
                .scopeId(orgAId)
                .title("TODOAUTHZ 組織TODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .progressManual(true)
                .createdBy(ownerOrgAId)
                .build());
        todoOrgAId = todoOrgA.getId();

        // PERSONAL TODO（所有者 = memberTeamA）: PERSONAL scope の所有権認可（membership でなく scopeId=userId）を検証する。
        TodoEntity todoPersonal = todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(memberTeamAId)
                .title("TODOAUTHZ 個人TODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .progressManual(true)   // setProgressRate（手動モード必須）の PERSONAL 正常系のため手動モードで作る
                .createdBy(memberTeamAId)
                .build());
        todoPersonalId = todoPersonal.getId();

        // 担当者行（todo_assignees）: 個人TODOの参照・更新・ステータス変更EPの認可境界は「担当者であること」。
        // 本番では createPersonalTodo が作成者を担当者へ自動追加するが、本 IT は Repository 直 seed で
        // Controller を経由しないため、同じ状態を明示的に作る（これが無いと所有者本人の正常系が 404 になる）。
        assigneeRepository.save(TodoAssigneeEntity.builder()
                .todoId(todoPersonalId)
                .userId(memberTeamAId)
                .assignedBy(memberTeamAId)
                .build());

        // 個人メモ（todoPersonal・本人 = memberTeamA）: PERSONAL 個人メモ EP の正常系用。
        personalMemoRepository.save(TodoPersonalMemoEntity.builder()
                .todoId(todoPersonalId)
                .userId(memberTeamAId)
                .memo("PR-B PERSONAL 個人メモ")
                .build());

        // 共有メモ（投稿者 = memberTeamA）: update/delete の owner 照合系の正常系・非owner系に使う。
        TodoSharedMemoEntryEntity sharedMemo = sharedMemoRepository.save(TodoSharedMemoEntryEntity.builder()
                .todoId(todoTeamAId)
                .userId(memberTeamAId)
                .memo("PR-B 共有メモ")
                .build());
        sharedMemoTeamAId = sharedMemo.getId();

        // 個人メモ（本人 = memberTeamA）: my-memo GET/DELETE の正常系に使う。
        TodoPersonalMemoEntity personalMemo = personalMemoRepository.save(TodoPersonalMemoEntity.builder()
                .todoId(todoTeamAId)
                .userId(memberTeamAId)
                .memo("PR-B 個人メモ")
                .build());
        personalMemoTeamAId = personalMemo.getId();

        // team A スコープの未連携スケジュール（link-schedule 正常系用）。createScheduleFromTodo と同一の必須列。
        ScheduleEntity schedule = scheduleRepository.save(ScheduleEntity.builder()
                .title("PR-B 連携用スケジュール")
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .createdBy(ownerTeamAId)
                .teamId(teamAId)
                .startAt(LocalDateTime.now())
                .endAt(LocalDateTime.now().plusHours(1))
                .build());
        scheduleTeamAId = schedule.getId();

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 進捗率 / 進捗モード（Controller で verifyScopeAndMembership・共有メソッド保護）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. PATCH /todos/{id}/progress・/progress-mode（進捗・生IDOR根治）")
    class Progress {

        @Test
        @DisplayName("progress Team: 未認証は401")
        void progress_team_未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/progress", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressRate", 50))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("progress Team: 非メンバーは403")
        void progress_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/progress", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressRate", 50))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("progress Team: BOLA 他scope todoId→404秘匿（生IDOR根治）")
        void progress_team_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/progress", teamBSlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressRate", 50))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("progress Team: 正当メンバーは200")
        void progress_team_正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/progress", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressRate", 50))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("progress Org: 非メンバーは403 / 正当メンバーは200")
        void progress_org() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/todos/{id}/progress", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressRate", 50))))
                    .andExpect(status().isForbidden());
            setAuth(ownerOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/todos/{id}/progress", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressRate", 50))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("progress-mode Team: 非メンバー403 / BOLA404 / 正当メンバー200")
        void progressMode_team() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/progress-mode", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressManual", true))))
                    .andExpect(status().isForbidden());

            setAuth(memberTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/progress-mode", teamBSlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressManual", true))))
                    .andExpect(status().isNotFound());

            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/todos/{id}/progress-mode", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressManual", true))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 共有メモ GET/POST（verifyScopeAndMembership・Service 署名拡張）— Team + Org
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET/POST /todos/{id}/memos（共有メモ・scope 束縛＋membership）")
    class SharedMemoReadWrite {

        @Test
        @DisplayName("一覧 Team: 非メンバー403 / BOLA404 / 正当メンバー200")
        void list_team() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/memos", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());

            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/memos", teamBSlug, todoTeamAId))
                    .andExpect(status().isNotFound());

            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/memos", teamASlug, todoTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("追加 Team: 非メンバー403 / BOLA404 / 正当メンバー201")
        void add_team() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/memos", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "越境投稿"))))
                    .andExpect(status().isForbidden());

            setAuth(memberTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/memos", teamBSlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "越境投稿"))))
                    .andExpect(status().isNotFound());

            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/memos", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "正当な投稿"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("追加 Org: 非メンバー403 / 正当メンバー201")
        void add_org() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos/{id}/memos", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "越境投稿"))))
                    .andExpect(status().isForbidden());

            setAuth(ownerOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos/{id}/memos", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "正当な投稿"))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 共有メモ PUT/DELETE（前段ガード＋既存 owner / owner-or-admin 照合の維持）— Team
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PUT/DELETE /todos/{id}/memos/{memoId}（非メンバーは owner 照合前に 403）")
    class SharedMemoDestructive {

        @Test
        @DisplayName("編集 Team: 非メンバーは403（owner照合前に弾かれる）")
        void update_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamASlug, todoTeamAId, sharedMemoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "越境編集"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("編集 Team: BOLA 他scope todoId→404秘匿")
        void update_team_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamBSlug, todoTeamAId, sharedMemoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "越境編集"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("編集 Team: 投稿者本人（メンバー）は200")
        void update_team_投稿者は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamASlug, todoTeamAId, sharedMemoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "本人編集"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("編集 Team: 非投稿者メンバーは従来通り拒否（TODO_051→400）")
        void update_team_非投稿者は拒否() throws Exception {
            setAuth(ownerTeamAId); // メンバーだが投稿者ではない
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamASlug, todoTeamAId, sharedMemoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "非投稿者編集"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("削除 Team: 非メンバーは403（owner照合前に弾かれる）")
        void delete_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamASlug, todoTeamAId, sharedMemoTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除 Team: BOLA 他scope todoId→404秘匿")
        void delete_team_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamBSlug, todoTeamAId, sharedMemoTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("削除 Team: 投稿者本人は204")
        void delete_team_投稿者は204() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamASlug, todoTeamAId, sharedMemoTeamAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("削除 Team: ADMIN（非投稿者・メンバー）は204")
        void delete_team_ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamASlug, todoTeamAId, sharedMemoTeamAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("削除 Team: 非投稿者・非ADMINメンバーは従来通り拒否（TODO_051→400）")
        void delete_team_非投稿者非ADMINは拒否() throws Exception {
            setAuth(ownerTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/memos/{memoId}",
                            teamASlug, todoTeamAId, sharedMemoTeamAId))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 個人メモ GET/PUT/DELETE（verifyScopeAndMembership・Service 署名拡張）— Team + Org
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. /todos/{id}/my-memo（個人メモ・scope 束縛＋membership）")
    class PersonalMemo {

        @Test
        @DisplayName("取得 Team: 非メンバー403 / BOLA404 / 正当メンバー200")
        void get_team() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());

            setAuth(memberTeamBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamBSlug, todoTeamAId))
                    .andExpect(status().isNotFound());

            setAuth(memberTeamAId); // 本人の個人メモを seed 済み
            mockMvc.perform(get("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamASlug, todoTeamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("UPSERT Team: 非メンバー403 / BOLA404 / 正当メンバー200")
        void upsert_team() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "越境個人メモ"))))
                    .andExpect(status().isForbidden());

            setAuth(memberTeamBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamBSlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "越境個人メモ"))))
                    .andExpect(status().isNotFound());

            setAuth(ownerTeamAId); // 新規 upsert（本人の個人メモは未 seed）
            mockMvc.perform(put("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "正当な個人メモ"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("削除 Team: 非メンバー403 / BOLA404 / 正当メンバー204")
        void delete_team() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());

            setAuth(memberTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamBSlug, todoTeamAId))
                    .andExpect(status().isNotFound());

            setAuth(memberTeamAId); // 本人の個人メモを seed 済み
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/my-memo", teamASlug, todoTeamAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("取得 Org: 非メンバー403")
        void get_org_非メンバーは403() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/todos/{id}/my-memo", orgAId, todoOrgAId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. スケジュール連携 POST/DELETE（verifyScopeAndMembership・Service 署名拡張）— Team + Org
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. /todos/{id}/link-schedule（連携・scope 束縛＋membership）")
    class ScheduleLink {

        @Test
        @DisplayName("連携 Team: 非メンバーは403")
        void link_team_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/link-schedule", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("scheduleId", scheduleTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("連携 Team: BOLA 他scope todoId→404秘匿")
        void link_team_BOLAは404秘匿() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/link-schedule", teamBSlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("scheduleId", scheduleTeamAId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("連携 Team: 正当メンバーは200")
        void link_team_正当メンバーは200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/todos/{id}/link-schedule", teamASlug, todoTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("scheduleId", scheduleTeamAId))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("解除 Team: 非メンバー403 / BOLA404 / 正当メンバー204（未連携でも no-op で成功）")
        void unlink_team() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/link-schedule", teamASlug, todoTeamAId))
                    .andExpect(status().isForbidden());

            setAuth(memberTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/link-schedule", teamBSlug, todoTeamAId))
                    .andExpect(status().isNotFound());

            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/todos/{id}/link-schedule", teamASlug, todoTeamAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("連携 Org: 非メンバー403 / BOLA404")
        void link_org() throws Exception {
            setAuth(memberOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos/{id}/link-schedule", orgAId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("scheduleId", 999999))))
                    .andExpect(status().isForbidden());

            setAuth(memberOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/todos/{id}/link-schedule", orgBId, todoOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("scheduleId", 999999))))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. PERSONAL スコープ（所有権認可・membership を経由せず 500 化しないことの回帰防止）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. PERSONAL /todos/{id}/memo（所有権 scopeId=userId・membership 非経由）")
    class PersonalScopeMemo {

        @Test
        @DisplayName("取得: 所有者本人は200")
        void get_所有者は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/todos/{id}/memo", todoPersonalId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("取得: 他人（非所有者）は404秘匿（PERSONALをmembshipに渡さず500化しない）")
        void get_他人は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/todos/{id}/memo", todoPersonalId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("UPSERT: 所有者本人は200")
        void upsert_所有者は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/todos/{id}/memo", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "本人更新"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("UPSERT: 他人（非所有者）は404秘匿")
        void upsert_他人は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/todos/{id}/memo", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo", "越境更新"))))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PERSONAL スコープ 進捗率/進捗モード（早馬 追加・Controller 入口で所有権認可）
    //    setProgressRate/setProgressMode は ActionMemoService からも呼ばれる共有メソッドのため
    //    PersonalTodoController 入口で verifyScopeAndMembership(PERSONAL, userId, userId) を敷く。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. PERSONAL /todos/{id}/progress・/progress-mode（所有権 scopeId=userId・生IDOR根治）")
    class PersonalScopeProgress {

        @Test
        @DisplayName("progress: 所有者本人は200")
        void progress_所有者は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/todos/{id}/progress", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressRate", 50))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("progress: 他人（非所有者）は404秘匿（PERSONALをmembershipに渡さず500化しない）")
        void progress_他人は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/todos/{id}/progress", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressRate", 50))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("progress-mode: 所有者本人は200")
        void progressMode_所有者は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/todos/{id}/progress-mode", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressManual", true))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("progress-mode: 他人（非所有者）は404秘匿")
        void progressMode_他人は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/todos/{id}/progress-mode", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("progressManual", true))))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. PERSONAL 参照・更新・ステータス変更（Wave6）
    //    getTodo/updateTodo/changeStatus は Team/Org Controller と共有のため、
    //    ガードは共有 Service ではなく PersonalTodoController 入口に敷く。
    //    認可境界は削除・復元・PATCH と同一の「担当者であること」に揃える。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PERSONAL /todos/{id}（参照・更新・status・toggle）担当者照合")
    class PersonalScopeReadWrite {

        /** PUT のボディは title が @NotBlank。埋めないと bind 時 400 になり認可へ到達しない。 */
        private Map<String, Object> validUpdateBody() {
            return Map.of("title", "更新後タイトル", "priority", "MEDIUM");
        }

        @Test
        @DisplayName("参照: 担当者本人は200")
        void get_担当者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/todos/{id}", todoPersonalId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("参照: 他人（非担当者）は404秘匿")
        void get_他人は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/todos/{id}", todoPersonalId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("更新(PUT): 担当者本人は200")
        void put_担当者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(put("/api/v1/todos/{id}", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(validUpdateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("更新(PUT): 他人（非担当者）は404秘匿（ボディは妥当＝bind400ではないこと）")
        void put_他人は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/todos/{id}", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(validUpdateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("status: 担当者本人は200")
        void status_担当者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/todos/{id}/status", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "COMPLETED"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("status: 他人（非担当者）は404秘匿")
        void status_他人は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/todos/{id}/status", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "COMPLETED"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("toggle: 担当者本人は200")
        void toggle_担当者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/todos/{id}/toggle", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("completed", true))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("toggle: 他人（非担当者）は404秘匿（status EP の姉妹EPも同一境界であること）")
        void toggle_他人は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/todos/{id}/toggle", todoPersonalId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("completed", true))))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（seed 列は TodoScopeContractIT / ScheduleWriteScopeContractIT を verbatim 写経）
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
