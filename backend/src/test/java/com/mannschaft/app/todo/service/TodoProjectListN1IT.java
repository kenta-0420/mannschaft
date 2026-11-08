package com.mannschaft.app.todo.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.ProjectVisibility;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TODO一覧・プロジェクト一覧の N+1 解消を固定する試練（CMP-261103-1225 / P1-D）。
 *
 * <p>SQL 数が todo 件数・担当者数・プロジェクト件数・マイルストーン数・作成者の種類数に
 * 依存しないことを Hibernate {@link Statistics#getPrepareStatementCount()} で検証し、
 * 応答内容（担当者の並び・表示名・件数・meta）が従来と等価であることを特性化する。</p>
 */
@Transactional
@DisplayName("TODO・プロジェクト一覧 N+1 解消（P1-D）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TodoProjectListN1IT extends AbstractMySqlIntegrationTest {

    @Autowired
    private TodoService todoService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private TodoAssigneeRepository assigneeRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMilestoneRepository milestoneRepository;
    @PersistenceContext
    private EntityManager em;

    private Statistics stats() {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        s.clear();
        return s;
    }

    private Long insertUser(String displayName) {
        // Repository 経由で保存し、Entity の builder 既定値を効かせる（BACKEND_CODING_CONVENTION のフィクスチャ規約）
        return userRepository.saveAndFlush(UserEntity.builder()
                .email(UUID.randomUUID() + "@n1.invalid")
                .lastName("N1").firstName("テスト").displayName(displayName)
                .locale("ja").timezone("Asia/Tokyo").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }

    /** 退会（論理削除）済みユーザーを作る。JPA からは見えず、表示名は空になる。 */
    private Long insertWithdrawnUser() {
        Long id = insertUser("退会済み");
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        return id;
    }

    private Long insertTeam() {
        String slug = "n1-" + Long.toString(System.nanoTime(), 36);
        return teamRepository.saveAndFlush(TeamEntity.builder().name("N1チーム").slug(slug)
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build()).getId();
    }

    private TodoEntity todo(Long teamId, String title, Long createdBy) {
        return todoRepository.save(TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM).scopeId(teamId).title(title)
                .status(TodoStatus.OPEN).priority(TodoPriority.MEDIUM)
                .sortOrder(0).createdBy(createdBy).build());
    }

    private void assign(TodoEntity t, Long userId) {
        assigneeRepository.save(TodoAssigneeEntity.builder()
                .todoId(t.getId()).userId(userId).assignedBy(userId).build());
    }

    private ProjectEntity project(Long teamId, String title, Long createdBy) {
        return projectRepository.save(ProjectEntity.builder()
                .scopeType(TodoScopeType.TEAM).scopeId(teamId).title(title)
                .status(ProjectStatus.ACTIVE).visibility(ProjectVisibility.MEMBERS_ONLY)
                .createdBy(createdBy).build());
    }

    private void milestones(ProjectEntity p, int total, int completed) {
        for (int i = 0; i < total; i++) {
            milestoneRepository.save(ProjectMilestoneEntity.builder()
                    .projectId(p.getId()).title("M" + i).sortOrder((short) i)
                    .isCompleted(i < completed).build());
        }
    }

    private PagedResponse<TodoResponse> listTodos(Long teamId) {
        return todoService.listTodos(TodoScopeType.TEAM, teamId, null, 0, 50, "RECENT");
    }

    // ---------------- TODO 一覧 ----------------

    @Test
    @DisplayName("AC-1/AC-2/AC-4/AC-7: todo 件数・担当者数が増えても SQL 数は一定／担当者の並びと表示名は従来どおり／他チーム混入なし")
    void todoListSqlCountIndependentAndContentEquivalent() {
        Long alice = insertUser("アリス");
        Long bob = insertUser("ボブ");
        Long carol = insertUser("キャロル");

        // 小: todo1件・担当者1名
        Long teamSmall = insertTeam();
        assign(todo(teamSmall, "S1", alice), alice);

        // 大: todo 12 件。同じ担当者が複数 todo に重複（AC-4）、担当者数は 0〜3 名
        Long teamLarge = insertTeam();
        List<TodoEntity> large = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            TodoEntity t = todo(teamLarge, "L" + i, i % 2 == 0 ? alice : bob);
            large.add(t);
            int n = i % 4; // 0,1,2,3
            if (n >= 1) assign(t, alice);
            if (n >= 2) assign(t, bob);
            if (n >= 3) assign(t, carol);
        }
        // 他チームの todo（混入しないこと）
        Long other = insertTeam();
        assign(todo(other, "OTHER", carol), carol);
        em.flush();
        em.clear();

        // systemDefaultLabels 等のキャッシュ初回 SQL が小さい側だけに乗らないよう、測定前に温める
        listTodos(teamSmall);
        em.clear();

        Statistics s = stats();
        PagedResponse<TodoResponse> small = listTodos(teamSmall);
        long smallCount = s.getPrepareStatementCount();
        assertThat(small.getData()).hasSize(1);

        em.clear();
        s.clear();
        PagedResponse<TodoResponse> big = listTodos(teamLarge);
        long bigCount = s.getPrepareStatementCount();
        System.out.println("P1D todoList SQL small=" + smallCount + " large=" + bigCount);

        assertThat(big.getData()).hasSize(12);
        assertThat(big.getData()).noneMatch(r -> "OTHER".equals(r.getContent().title()));
        assertThat(bigCount).as("12件でも1件と同じ SQL 数").isEqualTo(smallCount);

        // 特性化: 担当者の並び（担当者ID昇順＝付与順）・表示名・作成者名
        Map<String, TodoResponse> byTitle = big.getData().stream()
                .collect(Collectors.toMap(r -> r.getContent().title(), Function.identity()));
        assertThat(byTitle.get("L0").getAssignees()).isEmpty();
        assertThat(byTitle.get("L1").getAssignees()).extracting("displayName").containsExactly("アリス");
        assertThat(byTitle.get("L2").getAssignees()).extracting("displayName").containsExactly("アリス", "ボブ");
        assertThat(byTitle.get("L3").getAssignees()).extracting("displayName")
                .containsExactly("アリス", "ボブ", "キャロル");
        assertThat(byTitle.get("L0").getAudit().createdBy().getDisplayName()).isEqualTo("アリス");
        assertThat(byTitle.get("L1").getAudit().createdBy().getDisplayName()).isEqualTo("ボブ");
        assertThat(big.getMeta().getTotal()).isEqualTo(12);
    }

    @Test
    @DisplayName("AC-3: todo が0件でも空 IN のエラーを出さず空一覧を返す")
    void todoListEmpty() {
        Long team = insertTeam();
        em.flush();
        em.clear();
        PagedResponse<TodoResponse> r = listTodos(team);
        assertThat(r.getData()).isEmpty();
        assertThat(r.getMeta().getTotal()).isZero();
    }

    @Test
    @DisplayName("AC-5/AC-6: 退会した担当者・作成者、実在しない担当者は表示名が空で NPE を出さない")
    void todoListWithdrawnAndMissingUsers() {
        Long alice = insertUser("アリス");
        Long withdrawn = insertWithdrawnUser();
        Long ghost = 987_654_321L;
        Long team = insertTeam();
        TodoEntity t = todo(team, "T", withdrawn);
        assign(t, alice);
        assign(t, withdrawn);
        assign(t, ghost);
        em.flush();
        em.clear();

        TodoResponse r = listTodos(team).getData().get(0);
        assertThat(r.getAssignees()).extracting("displayName").containsExactly("アリス", "", "");
        assertThat(r.getAudit().createdBy().getDisplayName()).isEmpty();
    }

    // ---------------- プロジェクト一覧 ----------------

    @Test
    @DisplayName("AC-1/AC-2/AC-8: プロジェクト件数・マイルストーン数・作成者の種類数が増えても SQL 数は一定／件数0は0扱い")
    void projectListSqlCountIndependentAndContentEquivalent() {
        Long alice = insertUser("アリス");
        Long bob = insertUser("ボブ");
        Long withdrawn = insertWithdrawnUser();

        Long teamSmall = insertTeam();
        milestones(project(teamSmall, "P-S", alice), 1, 0);

        Long teamLarge = insertTeam();
        Long[] creators = {alice, bob, withdrawn};
        for (int i = 0; i < 9; i++) {
            ProjectEntity p = project(teamLarge, "P" + i, creators[i % 3]);
            // マイルストーン: P0=0件, P1=1件(完了0), P2=3件(完了2), 以降 i%4 件
            if (i == 2) milestones(p, 3, 2);
            else if (i != 0) milestones(p, i % 4, i % 2);
        }
        Long other = insertTeam();
        milestones(project(other, "P-OTHER", alice), 2, 2);
        em.flush();
        em.clear();

        // キャッシュ・遅延初期化の初回 SQL が小さい側だけに乗らないよう、測定前に温める
        projectService.listProjects(TodoScopeType.TEAM, teamSmall, ProjectStatus.ACTIVE, 0, 50);
        em.clear();

        Statistics s = stats();
        PagedResponse<ProjectResponse> small =
                projectService.listProjects(TodoScopeType.TEAM, teamSmall, ProjectStatus.ACTIVE, 0, 50);
        long smallCount = s.getPrepareStatementCount();
        assertThat(small.getData()).hasSize(1);

        em.clear();
        s.clear();
        PagedResponse<ProjectResponse> big =
                projectService.listProjects(TodoScopeType.TEAM, teamLarge, ProjectStatus.ACTIVE, 0, 50);
        long bigCount = s.getPrepareStatementCount();
        System.out.println("P1D projectList SQL small=" + smallCount + " large=" + bigCount);

        assertThat(big.getData()).hasSize(9);
        assertThat(big.getData()).noneMatch(r -> "P-OTHER".equals(r.getTitle()));
        assertThat(bigCount).as("9件でも1件と同じ SQL 数").isEqualTo(smallCount);

        Map<String, ProjectResponse> byTitle = big.getData().stream()
                .collect(Collectors.toMap(ProjectResponse::getTitle, Function.identity()));
        assertThat(byTitle.get("P0").getMilestones().getTotal()).isZero();
        assertThat(byTitle.get("P0").getMilestones().getCompleted()).isZero();
        assertThat(byTitle.get("P2").getMilestones().getTotal()).isEqualTo(3);
        assertThat(byTitle.get("P2").getMilestones().getCompleted()).isEqualTo(2);
        assertThat(byTitle.get("P0").getCreatedBy().getDisplayName()).isEqualTo("アリス");
        assertThat(byTitle.get("P1").getCreatedBy().getDisplayName()).isEqualTo("ボブ");
        assertThat(byTitle.get("P2").getCreatedBy().getDisplayName()).isEmpty(); // 退会者
        assertThat(big.getMeta().getTotal()).isEqualTo(9);
    }

    @Test
    @DisplayName("AC-3: プロジェクトが0件でも空 IN のエラーを出さず空一覧を返す")
    void projectListEmpty() {
        Long team = insertTeam();
        em.flush();
        em.clear();
        PagedResponse<ProjectResponse> r =
                projectService.listProjects(TodoScopeType.TEAM, team, ProjectStatus.ACTIVE, 0, 50);
        assertThat(r.getData()).isEmpty();
    }
}
