package com.mannschaft.app.pointcard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.pointcard.dto.UserPointCardDetailResponse;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
import org.springframework.data.redis.core.ValueOperations;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP1027 個人wallet主要5操作の既済GREEN補強。実JWT/filter/MySQLを通し、
 * foreignとmissingの完全404本文、拒否の全列不変とtoken未発行を固定する。
 * 外側TXを持たずRepository fixtureをcommitする。DDLはentity由来で、FlywayのFK証明ではない。
 * 組織操作・public・ADMINによる所有権救済は対象に含めない。
 */
@AutoConfigureMockMvc(addFilters = true)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("CMP1027 個人wallet子IDの完全本文契約")
class PointCardWalletOwnerBodyContractIT extends AbstractMySqlIntegrationTest {

    private static final List<String> OPERATIONS = List.of("get", "patch", "delete", "used", "share");
    private static final String TOKEN_PREFIX = "mannschaft:wallet:share_token:";
    private static final Duration ASYNC_LIMIT = Duration.ofSeconds(5);
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private UserPointCardRepository cards;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<UUID> cardIds = new ArrayList<>();
    private Long owner;
    private Long outsider;
    private UUID own;
    private UUID foreign;
    private UUID missing;
    private ValueOperations<String, String> values;
    private final Map<String, String> issuedTokens = new LinkedHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void 暗号化対象をRepositoryで正規保存する() throws Exception {
        awaitAuditIdle();
        values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(values.setIfAbsent(argThat(key -> key != null && key.startsWith(TOKEN_PREFIX)),
                anyString(), any(Duration.class))).thenAnswer(call -> {
                    assertThat(call.getArgument(2, Duration.class)).isEqualTo(Duration.ofMinutes(5));
                    issuedTokens.put(call.getArgument(0), call.getArgument(1));
                    return true;
                });
        tx(() -> {
            owner = user(); outsider = user();
            own = card(owner, "本人カード"); foreign = card(outsider, "他人カード");
            missing = UUID.randomUUID();
            assertThat(cards.existsById(missing)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 監査終了後だけ自所有DB行を削除する() throws Exception {
        try {
            try { awaitAuditIdle(); }
            catch (Exception unfinished) {
                throw new IllegalStateException("監査未終了のためfixtureを保全: users=" + userIds
                        + ", cards=" + cardIds, unfinished);
            }
            tx(() -> {
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM audit_logs WHERE user_id IN (:ids)")
                            .setParameter("ids", userIds).executeUpdate();
                }
                cards.deleteAllById(cardIds); em.flush();
                users.deleteAllById(userIds); em.flush();
                return null;
            });
        } finally {
            // AuditLogService writerはDBのみ。未終了時も共有mockと認証は競合なく復元できる。
            try { reset(redisTemplate); } finally { SecurityContextHolder.clearContext(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "patch", "delete", "used", "share"})
    void 他人カードは不在と完全同一で全列とtokenが不変(String operation) throws Exception {
        Map<String, String> before = snapshot(null, false);
        try {
            MvcResult absent = http(operation, owner, missing);
            assertNotFound(absent);
            MvcResult denied = http(operation, owner, foreign);
            assertNotFound(denied);
            assertThat(json(denied)).isEqualTo(json(absent));
            assertThat(denied.getResponse().getContentType()).isEqualTo(absent.getResponse().getContentType());
        } finally { assertUnchanged(before); assertNoToken(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "patch", "delete", "used", "share"})
    void 本人5操作だけ正当な効果を持つ(String operation) throws Exception {
        UUID excluded = operation.equals("get") || operation.equals("share") ? null : own;
        Map<String, String> otherBefore = snapshot(excluded, operation.equals("delete"));
        ObjectNode cardBefore = detail(own);
        assertThat(cardBefore.deepCopy().retain("id", "displayName", "nickname", "barcodeValue", "barcodeFormat",
                "last4", "memo", "favorite", "displayOrder", "providerMatched")).isEqualTo(mapper.valueToTree(Map.of(
                        "id", own.toString(), "displayName", "本人カード", "nickname", "原呼称",
                        "barcodeValue", "1234567890123", "barcodeFormat", "CODE128", "last4", "0123",
                        "memo", "原メモ", "favorite", false, "displayOrder", 2, "providerMatched", false)));
        Instant start = Instant.now();
        try {
            MvcResult result = http(operation, owner, own);
            assertThat(result.getResponse().getStatus()).isEqualTo(operation.equals("share") ? 201
                    : operation.equals("delete") || operation.equals("used") ? 204 : 200);
            switch (operation) {
                case "get" -> {
                    assertThat(json(result).path("data")).isEqualTo(cardBefore);
                    assertThat(detail(own)).isEqualTo(cardBefore);
                    assertNoToken();
                }
                case "patch" -> {
                    ObjectNode expected = cardBefore.deepCopy();
                    expected.put("nickname", "更新呼称"); expected.put("memo", "更新メモ");
                    expected.put("favorite", true); expected.put("displayOrder", 7);
                    ObjectNode persisted = detail(own);
                    assertThat(Instant.parse(persisted.path("updatedAt").asText())).isBetween(start, Instant.now());
                    // DTOはcommit前、PreUpdateはflush時。応答にDB更新後時刻を要求する仕様は追加しない。
                    ObjectNode response = (ObjectNode) json(result).path("data");
                    assertThat(response.path("updatedAt")).isIn(cardBefore.path("updatedAt"), persisted.path("updatedAt"));
                    ObjectNode expectedResponse = expected.deepCopy();
                    expectedResponse.set("updatedAt", response.get("updatedAt"));
                    assertThat(response).isEqualTo(expectedResponse);
                    expected.set("updatedAt", persisted.get("updatedAt"));
                    assertThat(persisted).isEqualTo(expected);
                    assertNoToken();
                }
                case "used" -> {
                    assertThat(result.getResponse().getContentAsString()).isEmpty();
                    ObjectNode persisted = detail(own);
                    assertThat(Instant.parse(persisted.path("lastUsedAt").asText())).isBetween(start, Instant.now());
                    ObjectNode expected = cardBefore.deepCopy();
                    expected.set("lastUsedAt", persisted.get("lastUsedAt"));
                    expected.set("updatedAt", persisted.get("updatedAt"));
                    assertThat(persisted).isEqualTo(expected);
                    assertNoToken();
                }
                case "delete" -> {
                    assertThat(result.getResponse().getContentAsString()).isEmpty();
                    assertThat(tx(() -> cards.existsById(own))).isFalse();
                    awaitAuditIdle();
                    List<?> audit = tx(() -> em.createNativeQuery("SELECT event_type, metadata FROM audit_logs "
                            + "WHERE user_id = :user ORDER BY id").setParameter("user", owner).getResultList());
                    assertThat(audit).hasSize(1);
                    Object[] row = (Object[]) audit.get(0);
                    assertThat(row[0]).isEqualTo("POINT_CARD_DELETED");
                    assertThat(mapper.readTree(String.valueOf(row[1]))).isEqualTo(mapper.valueToTree(Map.of(
                            "card_id", own.toString(), "provider_code", "(none)", "provider_matched", false)));
                    assertNoToken();
                }
                case "share" -> {
                    JsonNode data = json(result).path("data");
                    String token = data.path("token").asText();
                    assertThat(UUID.fromString(token).version()).isEqualTo(4);
                    Instant expiresAt = Instant.parse(data.path("expiresAt").asText());
                    assertThat(expiresAt).isBetween(start.plusSeconds(300), Instant.now().plusSeconds(300));
                    assertThat(data).isEqualTo(mapper.valueToTree(Map.of("token", token,
                            "expiresAt", data.path("expiresAt").asText(),
                            "deepLinkUrl", "mannschaft://wallet/share?token=" + token)));
                    assertThat(issuedTokens).hasSize(1).containsKey(TOKEN_PREFIX + token);
                    JsonNode payload = mapper.readTree(issuedTokens.get(TOKEN_PREFIX + token));
                    // OffsetDateTime.toStringとJackson ISO formatterは同時点でも末尾0の表記が異なる。
                    assertThat(Instant.parse(payload.path("expiresAt").asText())).isEqualTo(expiresAt);
                    assertThat(payload).isEqualTo(mapper.valueToTree(Map.of("cardId", own.toString(),
                            "userId", owner, "expiresAt", payload.path("expiresAt").asText())));
                    assertThat(detail(own)).isEqualTo(cardBefore);
                }
                default -> throw new IllegalArgumentException(operation);
            }
        } finally {
            awaitAuditIdle();
            assertThat(snapshot(excluded, operation.equals("delete"))).isEqualTo(otherBefore);
        }
    }

    @Test
    void 未認証5操作は401で全列とtokenが不変() throws Exception {
        Map<String, String> before = snapshot(null, false);
        try {
            for (String operation : OPERATIONS) {
                assertThat(http(operation, null, own).getResponse().getStatus()).isEqualTo(401);
            }
        } finally { assertUnchanged(before); assertNoToken(); }
    }

    @Test
    void UUID型不正5操作は400で全列とtokenが不変() throws Exception {
        Map<String, String> before = snapshot(null, false);
        try {
            for (String operation : OPERATIONS) {
                assertThat(http(operation, owner, "invalid-uuid").getResponse().getStatus()).isEqualTo(400);
            }
        } finally { assertUnchanged(before); assertNoToken(); }
    }

    private MvcResult http(String operation, Long actor, Object id) throws Exception {
        String path = "/api/v1/point-cards/" + id;
        MockHttpServletRequestBuilder request = switch (operation) {
            case "get" -> get(path);
            case "patch" -> patch(path).contentType(MediaType.APPLICATION_JSON).content(
                    "{\"nickname\":\"更新呼称\",\"memo\":\"更新メモ\",\"favorite\":true,\"displayOrder\":7}");
            case "delete" -> delete(path);
            case "used" -> post(path + "/used");
            case "share" -> post(path + "/share-tokens");
            default -> throw new IllegalArgumentException(operation);
        };
        request.header("Accept-Language", "ja");
        if (actor != null) { request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER"))); }
        return mvc.perform(request).andReturn();
    }

    private void assertNotFound(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(json(result)).isEqualTo(mapper.valueToTree(Map.of("error", Map.of(
                "code", "POINT_CARD_006", "message", "指定されたカードは存在しません", "fieldErrors", List.of()))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertNoToken() {
        assertThat(issuedTokens).isEmpty();
        verify(values, never()).setIfAbsent(argThat(key -> key != null && key.startsWith(TOKEN_PREFIX)),
                anyString(), any(Duration.class));
    }

    private ObjectNode detail(UUID id) {
        return tx(() -> mapper.valueToTree(UserPointCardDetailResponse.from(cards.findById(id).orElseThrow(), null)));
    }

    private void assertUnchanged(Map<String, String> before) throws Exception {
        awaitAuditIdle(); assertThat(snapshot(null, false)).isEqualTo(before);
    }

    private Map<String, String> snapshot(UUID excludedCard, boolean excludeOwnerAudit) {
        return tx(() -> Map.of("users", rows("users", "id IN (:ids)", userIds),
                "cards", rows("user_point_cards", "id IN (:ids)", cardIds.stream()
                        .filter(id -> !id.equals(excludedCard)).map(UUID::toString).toList()),
                "audit", rows("audit_logs", "user_id IN (:ids)", userIds.stream()
                        .filter(id -> !excludeOwnerAudit || !id.equals(owner)).toList()),
                "groupItems", rows("point_card_group_items", "card_id IN (:ids)", cardIds.stream().map(UUID::toString).toList()),
                "balanceEvents", rows("point_card_balance_events", "card_id IN (:ids)", cardIds.stream().map(UUID::toString).toList()),
                "stampEvents", rows("point_card_stamp_events", "card_id IN (:ids)", cardIds.stream().map(UUID::toString).toList())));
    }

    private String rows(String table, String predicate, List<?> ids) {
        return Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " WHERE " + predicate + " ORDER BY id")
                .setParameter("ids", ids).getResultList().toArray());
    }

    private void awaitAuditIdle() throws Exception {
        eventPool.submit(() -> { }).get(ASYNC_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        await().alias("wallet監査event-poolの有限待機").atMost(ASYNC_LIMIT).pollInterval(Duration.ofMillis(20))
                .until(() -> eventPool.getActiveCount() == 0 && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private <T> T tx(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private Long user() {
        Long id = users.save(UserEntity.builder().email("wallet-" + UUID.randomUUID() + "@example.com")
                .lastName("契約").firstName("試験").displayName("wallet契約").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id); return id;
    }

    private UUID card(Long user, String name) {
        UUID id = cards.save(UserPointCardEntity.builder().userId(user).displayName(name).nickname("原呼称")
                .barcodeValue("1234567890123").barcodeFormat(BarcodeFormat.CODE128).last4("0123")
                .memo("原メモ").favorite(false).displayOrder(2).build()).getId();
        cardIds.add(id); return id;
    }
}
