package com.mannschaft.app.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.todo.entity.TodoAssigneeEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260821-1027 の既に統一済みの TODO 詳細GETだけを実経路で固定する。
 *
 * <p>実JWT・filters・Controller・Guard・Service・GHE・MySQLを通す。
 * TEAM/ORGは正当所属pathを固定し、personal入口は全scope横断の担当者境界を維持する。
 * 非所属pathの403、役割ごとの閲覧裁可、公開slug、親削除はこの契約に追加しない。
 * 共通MOCK基底を継承し、fixtureはRepositoryと独立TXで確定する。
 * 外部Redisだけ継承モックを空状態にし、実認証・認可をモックしない。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP1027 TODO詳細GETの本文完全同値とDB不変契約")
class TodoDetailScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING_ID = Long.MAX_VALUE;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private MembershipRepository memberships;
    @Autowired private TodoRepository todos;
    @Autowired private TodoAssigneeRepository assignees;
    @PersistenceContext private EntityManager em;

    private final List<Long> actorIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> membershipIds = new ArrayList<>();
    private final List<Long> todoIds = new ArrayList<>();
    private final List<Long> assigneeIds = new ArrayList<>();
    private final Map<TodoScopeType, Long> ownTodos = new EnumMap<>(TodoScopeType.class);
    private final Map<TodoScopeType, Long> foreignTodos = new EnumMap<>(TodoScopeType.class);
    private Long actor;
    private String ownTeamSlug;
    private Long ownOrg;
    private List<TableSnapshot> before;

    @BeforeEach
    void fixtureを独立TXで確定して実JWTの失効無し状態を用意する() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        SecurityContextHolder.clearContext();
        reset(redisTemplate);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        inTx(() -> {
            actor = fixtureUser("本人");
            Long foreignActor = fixtureUser("別担当者");
            TeamEntity ownTeam = fixtureTeam("本人チーム");
            TeamEntity foreignTeam = fixtureTeam("別チーム");
            ownTeamSlug = ownTeam.getSlug();
            ownOrg = fixtureOrganization("本人組織");
            Long foreignOrg = fixtureOrganization("別組織");
            fixtureMembership(actor, ScopeType.TEAM, ownTeam.getId());
            fixtureMembership(actor, ScopeType.ORGANIZATION, ownOrg);
            fixtureMembership(foreignActor, ScopeType.TEAM, foreignTeam.getId());
            fixtureMembership(foreignActor, ScopeType.ORGANIZATION, foreignOrg);
            ownTodos.put(TodoScopeType.PERSONAL, fixtureTodo(TodoScopeType.PERSONAL, actor, actor));
            ownTodos.put(TodoScopeType.TEAM, fixtureTodo(TodoScopeType.TEAM, ownTeam.getId(), actor));
            ownTodos.put(TodoScopeType.ORGANIZATION, fixtureTodo(TodoScopeType.ORGANIZATION, ownOrg, actor));
            foreignTodos.put(TodoScopeType.PERSONAL, fixtureTodo(TodoScopeType.PERSONAL, foreignActor, foreignActor));
            foreignTodos.put(TodoScopeType.TEAM, fixtureTodo(TodoScopeType.TEAM, foreignTeam.getId(), foreignActor));
            foreignTodos.put(TodoScopeType.ORGANIZATION, fixtureTodo(TodoScopeType.ORGANIZATION, foreignOrg, foreignActor));
            em.flush();
            for (long id : List.of(0L, -1L, Long.MAX_VALUE - 1, MISSING_ID)) {
                assertThat(todos.existsById(id)).isFalse();
            }
            return null;
        });
        before = snapshot();
        assertThat(before.stream().map(TableSnapshot::count).toList())
                .containsExactly(6L, 6L, 4L, 2L, 2L, 2L);
    }

    @AfterEach
    void 成否にかかわらず全列DB不変を再読して自所有fixtureだけ片付ける() {
        try {
            if (before != null) {
                assertThat(snapshot()).isEqualTo(before);
            }
        } finally {
            try {
                inTx(() -> {
                    assignees.deleteAllById(assigneeIds);
                    em.flush();
                    todos.deleteAllById(todoIds);
                    memberships.deleteAllById(membershipIds);
                    em.flush();
                    teams.deleteAllById(teamIds);
                    organizations.deleteAllById(orgIds);
                    em.flush();
                    users.deleteAllById(actorIds);
                    em.flush();
                    return null;
                });
            } finally {
                try {
                    reset(redisTemplate);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = TodoScopeType.class, names = {"TEAM", "ORGANIZATION"})
    void 正当所属pathの他scope子IDと不在は404の本文全体が同一(TodoScopeType scope) throws Exception {
        MvcResult foreign = http(scopedPath(scope, foreignTodos.get(scope))).andReturn();
        MvcResult missing = http(scopedPath(scope, MISSING_ID)).andReturn();
        assertSameNotFound(foreign, missing);
    }

    @ParameterizedTest
    @EnumSource(TodoScopeType.class)
    void personal入口の非担当者と不在は全scopeで404の本文全体が同一(TodoScopeType scope) throws Exception {
        MvcResult foreign = http(personalPath(foreignTodos.get(scope))).andReturn();
        MvcResult missing = http(personalPath(MISSING_ID)).andReturn();
        assertSameNotFound(foreign, missing);
    }

    @ParameterizedTest
    @EnumSource(value = TodoScopeType.class, names = {"TEAM", "ORGANIZATION"})
    void 正当所属で本人担当の子は同じ行を200取得できる(TodoScopeType scope) throws Exception {
        assertOwnTodo(http(scopedPath(scope, ownTodos.get(scope))), scope);
    }

    @ParameterizedTest
    @EnumSource(TodoScopeType.class)
    void personal入口は本人担当の全scopeの子を200取得できる(TodoScopeType scope) throws Exception {
        assertOwnTodo(http(personalPath(ownTodos.get(scope))), scope);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MAX_VALUE - 1})
    void 数値境界の不在は各入口で同じ404本文(long id) throws Exception {
        for (TodoScopeType scope : TodoScopeType.values()) {
            String path = scope == TodoScopeType.PERSONAL ? personalPath(id) : scopedPath(scope, id);
            String missing = scope == TodoScopeType.PERSONAL
                    ? personalPath(MISSING_ID) : scopedPath(scope, MISSING_ID);
            assertSameNotFound(http(path).andReturn(), http(missing).andReturn());
        }
    }

    @Test
    void 子IDの型不正は各入口で400を維持する() throws Exception {
        for (String prefix : detailPrefixes()) {
            http(prefix + "not-a-number").andExpect(status().isBadRequest());
        }
    }

    @Test
    void JWT無しは各入口で401を維持する() throws Exception {
        for (TodoScopeType scope : TodoScopeType.values()) {
            Long id = ownTodos.get(scope);
            String path = scope == TodoScopeType.PERSONAL ? personalPath(id) : scopedPath(scope, id);
            mockMvc.perform(get(path).header("Accept-Language", "ja"))
                    .andExpect(status().isUnauthorized());
        }
    }

    private ResultActions http(String path) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return mockMvc.perform(get(path).header("Accept-Language", "ja")
                .header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER"))));
    }

    private void assertOwnTodo(ResultActions result, TodoScopeType scope) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ownTodos.get(scope)))
                .andExpect(jsonPath("$.data.scope.scopeType").value(scope.name()))
                .andExpect(jsonPath("$.data.content.title").value("本人担当"));
    }

    private void assertSameNotFound(MvcResult first, MvcResult second) throws Exception {
        assertThat(first.getResponse().getStatus()).isEqualTo(404);
        assertThat(second.getResponse().getStatus()).isEqualTo(404);
        JsonNode firstBody = body(first);
        JsonNode secondBody = body(second);
        for (JsonNode response : List.of(firstBody, secondBody)) {
            assertThat(response.isObject()).isTrue();
            assertThat(response.has("data")).isFalse();
            JsonNode error = response.path("error");
            assertThat(error.isObject()).isTrue();
            assertThat(error.path("code").asText()).isEqualTo("TODO_010");
            assertThat(error.path("message").asText()).isEqualTo("TODOが見つかりません");
            assertThat(error.path("fieldErrors").isArray()).isTrue();
            assertThat(error.path("fieldErrors").size()).isZero();
        }
        assertThat(firstBody).isEqualTo(secondBody);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<TableSnapshot> snapshot() {
        return inTx(() -> {
            em.flush();
            em.clear();
            return List.of(
                    tableSnapshot("todos", "created_by", actorIds),
                    tableSnapshot("todo_assignees", "todo_id", todoIds),
                    tableSnapshot("memberships", "user_id", actorIds),
                    tableSnapshot("users", "id", actorIds),
                    tableSnapshot("teams", "id", teamIds),
                    tableSnapshot("organizations", "id", orgIds));
        });
    }

    private TableSnapshot tableSnapshot(String table, String column, List<Long> ids) {
        // table/columnは上の固定fixture表だけ。全列と件数を別クエリで再読する。
        String where = " FROM " + table + " WHERE " + column + " IN (:ids)";
        List<?> rows = em.createNativeQuery("SELECT *" + where + " ORDER BY id")
                .setParameter("ids", ids).getResultList();
        long count = ((Number) em.createNativeQuery("SELECT COUNT(*)" + where)
                .setParameter("ids", ids).getSingleResult()).longValue();
        return new TableSnapshot(table, Arrays.deepToString(rows.toArray()), count);
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long fixtureUser(String label) {
        Long id = users.save(UserEntity.builder()
                .email("todo-detail-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        actorIds.add(id);
        return id;
    }

    private TeamEntity fixtureTeam(String label) {
        TeamEntity team = teams.save(TeamEntity.builder().slug(fixtureSlug()).name(label)
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build());
        teamIds.add(team.getId());
        return team;
    }

    private Long fixtureOrganization(String label) {
        Long id = organizations.save(OrganizationEntity.builder().slug(fixtureSlug()).name(label)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        orgIds.add(id);
        return id;
    }

    private String fixtureSlug() {
        return "todo-" + UUID.randomUUID().toString().substring(0, 18);
    }

    private void fixtureMembership(Long user, ScopeType scope, Long id) {
        Long membership = memberships.save(MembershipEntity.builder().userId(user)
                .scopeType(scope).scopeId(id).roleKind(RoleKind.MEMBER).build()).getId();
        membershipIds.add(membership);
    }

    private Long fixtureTodo(TodoScopeType scope, Long scopeId, Long user) {
        Long id = todos.save(TodoEntity.builder().scopeType(scope).scopeId(scopeId)
                .title(user.equals(actor) ? "本人担当" : "別担当者").description("本文契約のfixture")
                .createdBy(user).build()).getId();
        todoIds.add(id);
        assigneeIds.add(assignees.save(TodoAssigneeEntity.builder().todoId(id)
                .userId(user).assignedBy(user).build()).getId());
        return id;
    }

    private String scopedPath(TodoScopeType scope, long id) {
        return scope == TodoScopeType.TEAM
                ? "/api/v1/teams/" + ownTeamSlug + "/todos/" + id
                : "/api/v1/organizations/" + ownOrg + "/todos/" + id;
    }

    private String personalPath(long id) {
        return "/api/v1/todos/" + id;
    }

    private List<String> detailPrefixes() {
        return List.of("/api/v1/teams/" + ownTeamSlug + "/todos/",
                "/api/v1/organizations/" + ownOrg + "/todos/", "/api/v1/todos/");
    }

    private record TableSnapshot(String table, String rows, long count) { }
}
