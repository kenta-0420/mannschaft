package com.mannschaft.app.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * CMP-260821-1027: 私的フレンド子IDの本文秘匿と既存認可のHTTP契約。
 * 実JWT/filter/controller/service/GHE/MySQLを通す。私的foreign本文のみRED対象で、
 * 非ADMIN107・正常204は現契約の固定。公開済み他チーム関係も不在と同一本文（CMP-260821-1027）。
 * 共通MOCKを継承し、fixtureを明示commitするため非同期監査も実DBで観測できる。
 * testのDDLはentity由来であり、本番FlywayのFK適用証明ではない。
 */
@AutoConfigureMockMvc(addFilters = true)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("私的フレンド子IDのHTTP契約")
class TeamFriendVisibilityScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;
    private static final String EVENT = "FRIEND_VISIBILITY_CHANGED";
    private static final Duration ASYNC_LIMIT = Duration.ofSeconds(5);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private MembershipRepository memberships;
    @Autowired private FollowRepository follows;
    @Autowired private TeamFriendRepository friends;
    @Autowired private CacheManager cacheManager;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> followIds = new ArrayList<>();
    private final List<Long> relationIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();
    private final Map<String, Long> actors = new LinkedHashMap<>();
    private final Map<Object, Object> previousCache = new HashMap<>();
    private final List<String> ownCacheKeys = new ArrayList<>();
    private final String sentinel = "cmp1027-social-" + UUID.randomUUID();
    private ConcurrentMapCache friendCache;
    private Long a;
    private Long b;
    private Long c;
    private Long ownPrivate;
    private Long foreignPrivate;
    private Long foreignPublic;

    @BeforeEach
    void 自所有fixtureをRepository経由で確定する() {
        SecurityContextHolder.clearContext();
        reset(redisTemplate);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        Cache cache = cacheManager.getCache("teamFriendList");
        assertThat(cache).isInstanceOf(ConcurrentMapCache.class);
        friendCache = (ConcurrentMapCache) cache;
        previousCache.putAll(friendCache.getNativeCache());
        friendCache.put(sentinel, "拒否経路の失効なし");
        tx(() -> {
            a = team(); b = team(); c = team();
            for (String role : List.of("ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN", "MEMBER", "SUPPORTER")) {
                role(role);
            }
            actor("A-admin-member", "ADMIN", a, RoleKind.MEMBER);
            actor("A-admin-role", "ADMIN", a, null);
            Long combined = actor("A-system-admin", "ADMIN", a, null);
            grant(combined, "SYSTEM_ADMIN", null);
            actor("B-admin-role", "ADMIN", b, null);
            actor("A-deputy", "DEPUTY_ADMIN", a, RoleKind.MEMBER);
            actor("A-member", null, a, RoleKind.MEMBER);
            actor("A-supporter", null, a, RoleKind.SUPPORTER);
            actor("outsider", null, null, null);
            actor("C-admin", "ADMIN", c, null);
            actor("system-only", "SYSTEM_ADMIN", null, null);
            ownPrivate = relation(a, b, false);
            foreignPrivate = relation(b, c, false);
            foreignPublic = relation(a, c, true);
            assertThat(friends.existsById(MISSING)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 監査完了時だけ所有行を削除し共有状態を復元する() throws Exception {
        try {
            try {
                awaitAuditIdle();
            } catch (Exception failure) {
                // 未終了writerが後から書く可能性がある。監査/actor/親/roleを消して競合させない。
                throw new IllegalStateException("監査writer未終了のため所有fixtureを保全: users=" + userIds
                        + ", teams=" + teamIds + ", relations=" + relationIds + ", follows=" + followIds
                        + ", createdRoles=" + createdRoleIds, failure);
            }
            tx(() -> {
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM audit_logs WHERE user_id IN (:ids) AND event_type = :event")
                            .setParameter("ids", userIds).setParameter("event", EVENT).executeUpdate();
                }
                friends.deleteAllById(relationIds);
                follows.deleteAllById(followIds);
                em.flush();
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)")
                            .setParameter("ids", userIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)")
                            .setParameter("ids", userIds).executeUpdate();
                    users.deleteAllById(userIds);
                }
                teams.deleteAllById(teamIds);
                roles.deleteAllById(createdRoleIds);
                return null;
            });
        } finally {
            try {
                if (friendCache != null) {
                    // AuditLogService.recordSyncのwriterはJPA監査保存のみでRedis/cacheを触らない。
                    // 本番allEntries失効で消えたbaselineだけ戻す。他担当の新規keyをclearしない。
                    friendCache.evict(sentinel);
                    previousCache.forEach((key, value) -> friendCache.getNativeCache().put(key, value));
                    ownCacheKeys.stream().filter(key -> !previousCache.containsKey(key)).forEach(friendCache::evict);
                }
            } finally {
                try { reset(redisTemplate); } finally { SecurityContextHolder.clearContext(); }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 私的foreignと不在は要求公開値によらず完全同一で副作用なし(boolean requestedPublic) throws Exception {
        for (String label : List.of("A-admin-member", "A-admin-role", "A-system-admin")) {
            Map<String, String> before = snapshot();
            try {
                MvcResult missing = http(actors.get(label), a, MISSING, Map.of("isPublic", requestedPublic));
                MvcResult foreign = http(actors.get(label), a, foreignPrivate, Map.of("isPublic", requestedPublic));
                assertError(missing, "SOCIAL_106", "フレンド関係が見つかりません");
                assertError(foreign, "SOCIAL_106", "フレンド関係が見つかりません");
                assertThat(foreign.getResponse().getContentType()).isEqualTo(missing.getResponse().getContentType());
                assertThat(json(foreign)).isEqualTo(json(missing));
            } finally { assertUnchanged(before); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 公開foreignも不在と完全同一で副作用なし(boolean requestedPublic) throws Exception {
        // CMP-260821-1027: 公開済みの他チーム関係も SOCIAL_106 に畳み、公開例外の 107 を廃した。
        Map<String, String> before = snapshot();
        try {
            MvcResult missing = http(actors.get("B-admin-role"), b, MISSING, Map.of("isPublic", requestedPublic));
            MvcResult foreign = http(actors.get("B-admin-role"), b, foreignPublic, Map.of("isPublic", requestedPublic));
            assertError(missing, "SOCIAL_106", "フレンド関係が見つかりません");
            assertError(foreign, "SOCIAL_106", "フレンド関係が見つかりません");
            assertThat(json(foreign)).isEqualTo(json(missing));
        } finally { assertUnchanged(before); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"A-deputy", "A-member", "A-supporter", "outsider", "C-admin", "system-only"})
    void 非ADMIN先行107は子とパスの実在に依存しない(String label) throws Exception {
        Map<String, String> before = snapshot();
        JsonNode expected = null;
        try {
            for (long path : List.of(a, MISSING)) {
                for (long child : List.of(ownPrivate, foreignPrivate, foreignPublic, MISSING)) {
                    MvcResult result = http(actors.get(label), path, child, Map.of("isPublic", true));
                    assertError(result, "SOCIAL_107", "公開設定の変更には ADMIN 権限が必要です");
                    if (expected != null) { assertThat(json(result)).isEqualTo(expected); }
                    expected = json(result);
                }
            }
        } finally { assertUnchanged(before); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"A-admin-member", "A-admin-role", "A-system-admin", "B-admin-role"})
    void 両側ADMINとrole単独とSYSTEM併有は204更新と実監査と自然失効を維持する(String label) throws Exception {
        Long actor = actors.get(label);
        Long path = label.equals("B-admin-role") ? b : a;
        String followBefore = snapshot().get("follows");
        String foreignBefore = tx(() -> rows("team_friends", "id IN (:ids)", List.of(foreignPrivate, foreignPublic)));
        for (boolean value : List.of(true, false)) {
            friendCache.put(sentinel, "更新前-" + value);
            MvcResult result = http(actor, path, ownPrivate, Map.of("isPublic", value));
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
            assertThat(tx(() -> friends.findById(ownPrivate).orElseThrow().getIsPublic())).isEqualTo(value);
            assertThat(friendCache.get(sentinel)).isNull();
            awaitAuditIdle();
            List<?> audit = tx(() -> em.createNativeQuery(
                            "SELECT user_id, team_id, metadata FROM audit_logs WHERE user_id = :actor AND event_type = :event ORDER BY id")
                    .setParameter("actor", actor).setParameter("event", EVENT).getResultList());
            assertThat(audit).hasSize(value ? 1 : 2);
            Object[] row = (Object[]) audit.get(audit.size() - 1);
            assertThat(((Number) row[0]).longValue()).isEqualTo(actor);
            assertThat(((Number) row[1]).longValue()).isEqualTo(path);
            assertThat(mapper.readTree(row[2].toString())).isEqualTo(mapper.readTree(mapper.writeValueAsString(Map.of(
                    "team_friend_id", ownPrivate, "is_public_before", !value, "is_public_after", value))));
        }
        assertThat(snapshot().get("follows")).isEqualTo(followBefore);
        assertThat(tx(() -> rows("team_friends", "id IN (:ids)", List.of(foreignPrivate, foreignPublic)))).isEqualTo(foreignBefore);
    }

    @Test
    void 有効Long境界と型本文不正と未認証の既存境界を維持する() throws Exception {
        Map<String, String> before = snapshot();
        Long actor = actors.get("A-admin-role");
        try {
            for (long id : List.of(0L, -1L, MISSING)) {
                assertError(http(actor, a, id, Map.of("isPublic", true)), "SOCIAL_106", "フレンド関係が見つかりません");
            }
            assertThat(http(actor, a, "invalid", Map.of("isPublic", true)).getResponse().getStatus()).isEqualTo(400);
            for (long id : List.of(ownPrivate, foreignPrivate, MISSING)) {
                assertThat(http(actor, a, id, Map.of()).getResponse().getStatus()).isEqualTo(400);
                assertThat(http(actor, a, id, java.util.Collections.singletonMap("isPublic", null))
                        .getResponse().getStatus()).isEqualTo(400);
            }
            assertThat(http(null, a, ownPrivate, Map.of("isPublic", true)).getResponse().getStatus()).isEqualTo(401);
        } finally { assertUnchanged(before); }
    }

    @Test
    void 一覧は所属境界とSUPPORTER公開子IDを維持する() throws Exception {
        for (String label : List.of("A-member", "A-supporter")) {
            MvcResult result = list(actors.get(label), a);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            List<Long> ids = new ArrayList<>();
            json(result).path("data").forEach(row -> ids.add(row.path("teamFriendId").asLong()));
            assertThat(ids).contains(foreignPublic);
            if (label.equals("A-supporter")) { assertThat(ids).doesNotContain(ownPrivate); }
            else { assertThat(ids).contains(ownPrivate); }
        }
        MvcResult outsider = list(actors.get("outsider"), a);
        MvcResult missing = list(actors.get("outsider"), MISSING);
        assertThat(outsider.getResponse().getStatus()).isEqualTo(403);
        assertThat(missing.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(outsider)).isEqualTo(json(missing));
    }

    private void assertError(MvcResult result, String code, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(json(result)).isEqualTo(mapper.valueToTree(Map.of("error", Map.of(
                "code", code, "message", message, "fieldErrors", List.of()))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult http(Long actor, Long path, Object child, Object body) throws Exception {
        MockHttpServletRequestBuilder request = patch("/api/v1/teams/{id}/friends/{child}/visibility", path, child)
                .header("Accept-Language", "ja").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
        authenticate(request, actor);
        return mvc.perform(request).andReturn();
    }

    private MvcResult list(Long actor, Long path) throws Exception {
        // QueryService: teamId:userId:page:size:publicOnly。自actor/pathのkeyだけcleanupする。
        if (path.equals(a) && (actor.equals(actors.get("A-member")) || actor.equals(actors.get("A-supporter")))) {
            ownCacheKeys.add(path + ":" + actor + ":0:20:" + actor.equals(actors.get("A-supporter")));
        }
        MockHttpServletRequestBuilder request = get("/api/v1/teams/{id}/friends", path).header("Accept-Language", "ja");
        authenticate(request, actor);
        return mvc.perform(request).andReturn();
    }

    private void authenticate(MockHttpServletRequestBuilder request, Long actor) {
        if (actor != null) {
            List<String> claims = actor.equals(actors.get("system-only")) || actor.equals(actors.get("A-system-admin"))
                    ? List.of("USER", "SYSTEM_ADMIN") : List.of("USER");
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, claims));
        }
    }

    private void awaitAuditIdle() throws Exception {
        // HTTP完了以前に投入された監査より後ろへbarrierを置き、未取得queueの空振りを避ける。
        eventPool.submit(() -> { }).get(ASYNC_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        await().alias("監査event-poolの有限待機").atMost(ASYNC_LIMIT)
                .pollInterval(Duration.ofMillis(20)).until(() -> eventPool.getActiveCount() == 0
                        && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private void assertUnchanged(Map<String, String> before) throws Exception {
        awaitAuditIdle();
        assertThat(snapshot()).isEqualTo(before);
    }

    private Map<String, String> snapshot() {
        return tx(() -> Map.of("friends", rows("team_friends", "id IN (:ids)", relationIds),
                "follows", rows("follows", "id IN (:ids)", followIds),
                "teams", rows("teams", "id IN (:ids)", teamIds),
                "audit", rows("audit_logs", "user_id IN (:ids)", userIds),
                "cacheSentinel", String.valueOf(friendCache.get(sentinel, String.class))));
    }

    private String rows(String table, String predicate, List<Long> ids) {
        return Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " WHERE " + predicate + " ORDER BY id")
                .setParameter("ids", ids).getResultList().toArray());
    }

    private <T> T tx(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private Long actor(String label, String role, Long team, RoleKind kind) {
        Long id = users.save(UserEntity.builder().email("social-contract-" + UUID.randomUUID() + "@example.com")
                .lastName("契約").firstName("試験").displayName("フレンド契約").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id); actors.put(label, id);
        if (role != null) { grant(id, role, team); }
        if (kind != null) {
            memberships.save(MembershipEntity.builder().userId(id).scopeType(ScopeType.TEAM).scopeId(team)
                    .roleKind(kind).joinedAt(LocalDateTime.now()).build());
        }
        return id;
    }

    private RoleEntity role(String name) {
        return roles.findByName(name).orElseGet(() -> {
            RoleEntity row = roles.save(RoleEntity.builder().name(name).displayName(name)
                    .priority(RolePriority.priority(name)).isSystem(name.equals("SYSTEM_ADMIN")).build());
            createdRoleIds.add(row.getId());
            return row;
        });
    }

    private void grant(Long actor, String name, Long team) {
        userRoles.save(UserRoleEntity.builder().userId(actor).roleId(role(name).getId()).teamId(team).build());
    }

    private Long team() {
        Long id = teams.save(TeamEntity.builder().slug("social-" + UUID.randomUUID().toString().substring(0, 20))
                .name("フレンド契約").visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(true).build()).getId();
        teamIds.add(id);
        return id;
    }

    private Long relation(Long first, Long second, boolean isPublic) {
        Long low = Math.min(first, second); Long high = Math.max(first, second);
        Long forward = follow(low, high); Long reverse = follow(high, low);
        Long id = friends.save(TeamFriendEntity.builder().teamAId(low).teamBId(high).aFollowId(forward)
                .bFollowId(reverse).isPublic(isPublic).establishedAt(LocalDateTime.now()).build()).getId();
        relationIds.add(id);
        return id;
    }

    private Long follow(Long first, Long second) {
        Long id = follows.save(FollowEntity.builder().followerType(FollowerType.TEAM).followerId(first)
                .followedType(FollowerType.TEAM).followedId(second).build()).getId();
        followIds.add(id);
        return id;
    }
}
