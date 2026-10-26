package com.mannschaft.app.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.matching.entity.MatchRequestTemplateEntity;
import com.mannschaft.app.matching.repository.MatchRequestTemplateRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * CMP-260821-1027: 私有チームのテンプレート子 ID に対する HTTP 契約。
 * 実 JWT・フィルター・認可・Service・例外変換を通し、独立 TX の全列再読で副作用を検査する。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("募集テンプレート子 ID の HTTP スコープ契約")
class MatchTemplateScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;
    private static final Map<String, String> VALID_BODY = Map.of("name", "更新後", "templateJson", "{\"marker\":2}");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private MatchRequestTemplateRepository templates;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> templateIds = new ArrayList<>();
    private final List<Long> writers = new ArrayList<>();
    /** この IT が新規作成した roles 行。既存行を get した場合は含めない（他 IT の get-or-create 前提を壊さないため）。 */
    private final List<Long> createdRoleIds = new ArrayList<>();
    private Long ownTeam;
    private Long foreignTeam;
    private Long ownTemplate;
    private Long foreignTemplate;
    private Long member;
    private Long outsider;

    @BeforeEach
    void 実在する私有チームと認証主体を独立TXで確定する() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        SecurityContextHolder.clearContext();
        reset(redisTemplate);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        tx(() -> {
            ownTeam = team();
            foreignTeam = team();
            for (String role : List.of("ADMIN", "DEPUTY_ADMIN")) {
                for (boolean membership : List.of(true, false)) {
                    Long actor = user();
                    userRole(actor, role, ownTeam);
                    if (membership) {
                        membership(actor);
                    }
                    writers.add(actor);
                }
            }
            Long system = user();
            userRole(system, "SYSTEM_ADMIN", null);
            writers.add(system);
            member = user();
            membership(member);
            outsider = user();
            ownTemplate = template(ownTeam);
            template(ownTeam);
            foreignTemplate = template(foreignTeam);
            assertThat(templates.existsById(MISSING)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 自所有fixtureと共有認証状態を必ず復元する() {
        try {
            tx(() -> {
                templates.deleteAllById(templateIds);
                em.flush();
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                    users.deleteAllById(userIds);
                }
                roles.deleteAllById(createdRoleIds);
                teams.deleteAllById(teamIds);
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

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void 管理者代理所属有無とSYSTEMで他チーム子と不在の全エラーが一致しDB不変(String method) throws Exception {
        for (Long actor : writers) {
            Map<String, String> before = snapshot();
            try {
                JsonNode missing = missingError(http(actor, method, MISSING, body(method)));
                JsonNode foreign = missingError(http(actor, method, foreignTemplate, body(method)));
                assertThat(foreign).isEqualTo(missing);
            } finally {
                assertThat(snapshot()).isEqualTo(before);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void 数値子ID境界は不在404でDB不変(String method) throws Exception {
        Map<String, String> before = snapshot();
        try {
            for (long id : List.of(0L, -1L, MISSING)) {
                missingError(http(writers.get(0), method, id, body(method)));
            }
        } finally {
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void MEMBERと無所属は子の実在にかかわらず経路権限403でDB不変(String method) throws Exception {
        Map<String, String> before = snapshot();
        try {
            for (Long actor : List.of(member, outsider)) {
                JsonNode expected = null;
                for (long id : List.of(ownTemplate, foreignTemplate, MISSING)) {
                    MvcResult result = http(actor, method, id, body(method));
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    JsonNode error = error(result);
                    assertThat(error.path("code").asText()).isEqualTo("COMMON_002");
                    assertThat(error.path("message").asText()).isEqualTo("この操作を行う権限がありません");
                    if (expected != null) {
                        assertThat(error).isEqualTo(expected);
                    }
                    expected = error;
                }
            }
        } finally {
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void 子ID型不正400と未認証401を維持しDB不変(String method) throws Exception {
        Map<String, String> before = snapshot();
        try {
            assertThat(http(writers.get(0), method, "invalid", body(method)).getResponse().getStatus()).isEqualTo(400);
            assertThat(http(null, method, ownTemplate, body(method)).getResponse().getStatus()).isEqualTo(401);
        } finally {
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void PUT本文不正は子の実在にかかわらず400でDB不変(Map<String, String> invalidBody) throws Exception {
        Map<String, String> before = snapshot();
        try {
            for (long id : List.of(ownTemplate, foreignTemplate, MISSING)) {
                assertThat(http(writers.get(0), "PUT", id, invalidBody).getResponse().getStatus()).isEqualTo(400);
            }
        } finally {
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    private static Stream<Map<String, String>> invalidBodies() {
        return Stream.of(Map.of("name", "", "templateJson", "{}"),
                Map.of("name", "長".repeat(51), "templateJson", "{}"), Map.of("name", "更新後"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void 正当な子操作は管理者代理所属有無とSYSTEMで成功し対象子だけが変わる(String method) throws Exception {
        for (Long actor : writers) {
            Long target = tx(() -> template(ownTeam));
            Map<String, String> before = snapshot();
            List<Long> untouchedIds = templateIds.stream().filter(id -> !id.equals(target)).toList();
            String untouchedBefore = tx(() -> rows("match_request_templates", "id IN (:ids)", untouchedIds));
            MvcResult result = http(actor, method, target, body(method));
            assertThat(result.getResponse().getStatus()).isEqualTo(method.equals("PUT") ? 200 : 204);
            Map<String, String> after = snapshot();
            assertThat(after.get("parents")).isEqualTo(before.get("parents"));
            assertThat(after.get("foreign")).isEqualTo(before.get("foreign"));
            assertThat(tx(() -> rows("match_request_templates", "id IN (:ids)", untouchedIds))).isEqualTo(untouchedBefore);
            tx(() -> {
                if (method.equals("PUT")) {
                    MatchRequestTemplateEntity row = templates.findById(target).orElseThrow();
                    assertThat(row.getId()).isEqualTo(target);
                    assertThat(row.getTeamId()).isEqualTo(ownTeam);
                    assertThat(row.getName()).isEqualTo("更新後");
                    assertThat(after.get("count")).isEqualTo(before.get("count"));
                } else {
                    assertThat(templates.existsById(target)).isFalse();
                    assertThat(Long.parseLong(after.get("count"))).isEqualTo(Long.parseLong(before.get("count")) - 1);
                }
                return null;
            });
            if (method.equals("PUT")) {
                String json = tx(() -> templates.findById(target).orElseThrow().getTemplateJson());
                assertThat(mapper.readTree(json)).isEqualTo(mapper.valueToTree(Map.of("marker", 2)));
            }
        }
    }

    private JsonNode missingError(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        JsonNode error = error(result);
        assertThat(error).isEqualTo(mapper.valueToTree(Map.of(
                "code", "MATCHING_018", "message", "テンプレートが見つかりません", "fieldErrors", List.of())));
        return error;
    }

    private JsonNode error(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("error");
    }

    private Object body(String method) { return method.equals("PUT") ? VALID_BODY : null; }

    private MvcResult http(Long actor, String method, Object id, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method),
                "/api/v1/teams/" + ownTeam + "/matching/templates/" + id).header("Accept-Language", "ja");
        if (actor != null) {
            builder.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER")));
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mvc.perform(builder).andReturn();
    }

    private Map<String, String> snapshot() {
        return tx(() -> Map.of(
                "parents", rows("teams", "id IN (:ids)", teamIds),
                "own", rows("match_request_templates", "team_id IN (:ids)", List.of(ownTeam)),
                "foreign", rows("match_request_templates", "team_id IN (:ids)", List.of(foreignTeam)),
                "count", em.createNativeQuery("SELECT COUNT(*) FROM match_request_templates WHERE team_id IN (:ids)")
                        .setParameter("ids", teamIds).getSingleResult().toString()));
    }

    private String rows(String table, String predicate, List<Long> ids) {
        return Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " WHERE " + predicate + " ORDER BY id")
                .setParameter("ids", ids).getResultList().toArray());
    }

    private <T> T tx(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private Long user() {
        Long id = users.save(UserEntity.builder().email("template-contract-" + UUID.randomUUID() + "@example.com")
                .lastName("契約").firstName("試験").displayName("テンプレート契約").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long role(String name) {
        return roles.findByName(name).orElseGet(() -> {
            RoleEntity created = roles.save(RoleEntity.builder()
                    .name(name).displayName(name).priority(RolePriority.priority(name)).isSystem(false).build());
            createdRoleIds.add(created.getId());
            return created;
        }).getId();
    }

    private void userRole(Long actor, String name, Long team) {
        userRoles.save(UserRoleEntity.builder().userId(actor).roleId(role(name)).teamId(team).build());
    }

    private void membership(Long actor) {
        role("MEMBER");
        memberships.save(MembershipEntity.builder().userId(actor).scopeType(ScopeType.TEAM)
                .scopeId(ownTeam).roleKind(RoleKind.MEMBER).build());
    }

    private Long team() {
        Long id = teams.save(TeamEntity.builder().slug("template-" + UUID.randomUUID().toString().substring(0, 20))
                .name("テンプレート契約").visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build()).getId();
        teamIds.add(id);
        return id;
    }

    private Long template(Long team) {
        Long id = templates.save(MatchRequestTemplateEntity.builder().teamId(team)
                .name("変更前").templateJson("{\"marker\":1}").build()).getId();
        templateIds.add(id);
        return id;
    }
}
