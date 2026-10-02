package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageJoinRequestEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageJoinRequestRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code VillageJoinRequestController#listMyHistory} の本人履歴と存在秘匿を実 HTTP・MySQL で固定する。
 *
 * <p>CMP-260826-1456: 村を特定する入力を受け取らず、申請時の requester だけを検索する。
 * 村の可視性・現在の代表権限によって本人の既存申請を失わず、第三者の村照会権限も広げない。
 * JWT 発行・認証フィルター・業務 Bean は実体を使い、Redis の外部接続だけ基底 fixture を使う。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-1456 本人の村参加申請履歴契約")
class VillageJoinRequestHistoryContractIT extends AbstractMySqlIntegrationTest {

    private static final String HISTORY_PATH = "/api/v1/village-join-requests/me";
    private static final Long ACTOR = 1_456_001L;
    private static final Long OTHER = 1_456_002L;
    private static final Long REVIEWER = 1_456_003L;
    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 8, 26, 14, 56);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AuthTokenService tokenService;
    @Autowired
    private VillageRepository villageRepository;
    @Autowired
    private VillageJoinRequestRepository requestRepository;
    @Autowired
    private VillageMembershipRepository membershipRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.get(anyString())).willReturn(null);
        given(redisTemplate.hasKey(anyString())).willReturn(false);
        for (Long actor : List.of(ACTOR, OTHER, REVIEWER)) {
            MembershipTestHelper.insertActiveUser(entityManager, actor);
        }
    }

    @Test
    @DisplayName("AC-1: 公開村への実申請はUNLISTED変更後も本人履歴に残る")
    void 公開時の申請を非公開化後も確認できる() throws Exception {
        VillageEntity village = village(VillageVisibility.PUBLIC);
        JsonNode submitted = body(as(ACTOR, post("/api/v1/villages/{id}/join-requests", village.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectType\":\"USER\",\"subjectId\":" + ACTOR
                        + ",\"message\":\"公開時の申請\"}"))
                .andExpect(status().isCreated()));
        village.setVisibility(VillageVisibility.UNLISTED);
        villageRepository.saveAndFlush(village);

        as(ACTOR, get(HISTORY_PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(submitted.path("data").path("id").asText()))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].message").value("公開時の申請"));
        as(ACTOR, get("/api/v1/villages/{id}/join-requests/me", village.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-2: 全状態とUSER/TEAM/ORGANIZATIONを申請時requesterだけで一覧する")
    void 主体と状態を跨いでも申請した本人だけに返す() throws Exception {
        VillageSubjectType[] subjects = VillageSubjectType.values();
        VillageRequestStatus[] statuses = VillageRequestStatus.values();
        for (int i = 0; i < statuses.length; i++) {
            VillageEntity village = village(VillageVisibility.UNLISTED);
            VillageSubjectType subject = subjects[i % subjects.length];
            request(village, ACTOR, subject, subject == VillageSubjectType.USER ? ACTOR : 8_000L + i,
                    statuses[i], SUBMITTED_AT.plusMinutes(i));
            request(village, OTHER, VillageSubjectType.TEAM, 9_000L + i,
                    VillageRequestStatus.PENDING, SUBMITTED_AT.plusDays(1));
        }
        JsonNode response = body(as(ACTOR, get(HISTORY_PATH)).andExpect(status().isOk()));
        assertThat(response.path("data").size()).isEqualTo(4);
        assertThat(response.path("meta").path("total").asLong()).isEqualTo(4);
        assertThat(response.path("data").findValuesAsText("status"))
                .containsExactly("WITHDRAWN", "REJECTED", "APPROVED", "PENDING");
        assertThat(response.path("data").findValuesAsText("subjectType"))
                .contains("USER", "TEAM", "ORGANIZATION");
        // 現在の代表ロールは一切付与していない。履歴は代表者一覧ではなく申請時の本人情報。
        assertThat(response.toString()).doesNotContain("villageName", "monsho", "秘密の村");
    }

    @Test
    @DisplayName("AC-2: 申請0件は既定の空配列200と総件数0")
    void 申請がなければ空の本人履歴を返す() throws Exception {
        as(ACTOR, get(HISTORY_PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(20));
    }

    @Test
    @DisplayName("AC-3: 他人の申請の有無と余分なuserId/villageIdで第三者応答は変わらない")
    void 他人の識別子を渡しても本人の空一覧のまま() throws Exception {
        String before = as(OTHER, get(HISTORY_PATH)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        VillageEntity hidden = village(VillageVisibility.UNLISTED);
        VillageJoinRequestEntity owned = request(hidden, ACTOR, VillageSubjectType.USER, ACTOR,
                VillageRequestStatus.PENDING, SUBMITTED_AT);
        String after = as(OTHER, get(HISTORY_PATH).param("userId", ACTOR.toString())
                .param("villageId", hidden.getId().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.total").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(after).isEqualTo(before).doesNotContain(owned.getId().toString(),
                hidden.getId().toString(), hidden.getName());
    }

    @ParameterizedTest
    @CsvSource({"-1,20,0,20", "0,0,0,1", "0,101,0,100"})
    @DisplayName("AC-4: 村一覧と同じpage0以上・size1..100への丸め")
    void ページ境界を既存規約へ丸める(int page, int size, int expectedPage, int expectedSize)
            throws Exception {
        as(ACTOR, get(HISTORY_PATH).param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(expectedPage))
                .andExpect(jsonPath("$.meta.size").value(expectedSize));
    }

    @ParameterizedTest
    @CsvSource({"page,abc", "size,abc"})
    @DisplayName("AC-4: 数値ではないページ指定は既存の型変換400")
    void 不正な数値は400(String parameter, String value) throws Exception {
        as(ACTOR, get(HISTORY_PATH).param(parameter, value)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-4: 履歴より先の空頁でも本人の総件数を失わない")
    void 空の後続ページに総件数が残る() throws Exception {
        request(village(VillageVisibility.UNLISTED), ACTOR, VillageSubjectType.USER, ACTOR,
                VillageRequestStatus.PENDING, SUBMITTED_AT);
        as(ACTOR, get(HISTORY_PATH).param("page", "8").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.page").value(8));
    }

    @Test
    @DisplayName("AC-4: 作成時刻が同じでもid降順でページの重複・欠落を防ぐ")
    void 同時刻をid降順で安定させる() throws Exception {
        VillageJoinRequestEntity first = request(village(VillageVisibility.UNLISTED), ACTOR,
                VillageSubjectType.USER, ACTOR, VillageRequestStatus.PENDING, SUBMITTED_AT);
        VillageJoinRequestEntity second = request(village(VillageVisibility.UNLISTED), ACTOR,
                VillageSubjectType.USER, ACTOR, VillageRequestStatus.PENDING, SUBMITTED_AT);
        List<String> expected = List.of(first.getId().toString(), second.getId().toString()).stream()
                .sorted(Comparator.reverseOrder()).toList();
        for (int page = 0; page < expected.size(); page++) {
            as(ACTOR, get(HISTORY_PATH).param("page", String.valueOf(page)).param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(expected.get(page)))
                    .andExpect(jsonPath("$.meta.total").value(2));
        }
    }

    @Test
    @DisplayName("AC-5: 未認証は実SecurityFilterChainで401")
    void 未認証の履歴を拒否する() throws Exception {
        mockMvc.perform(get(HISTORY_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-6: 第三者の非公開村と不存在は旧詳細・旧me・申請作成の同一404と副作用0")
    void 旧APIの存在秘匿を維持する() throws Exception {
        VillageEntity hidden = village(VillageVisibility.UNLISTED);
        request(hidden, ACTOR, VillageSubjectType.USER, ACTOR, VillageRequestStatus.PENDING, SUBMITTED_AT);
        UUID missing = UUID.randomUUID();
        long requestsBefore = requestRepository.count();
        long membershipsBefore = membershipRepository.count();
        for (String suffix : List.of("", "/join-requests/me")) {
            String hiddenBody = as(OTHER, get("/api/v1/villages/{id}" + suffix, hidden.getId()))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String missingBody = as(OTHER, get("/api/v1/villages/{id}" + suffix, missing))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(hiddenBody).isEqualTo(missingBody).doesNotContain(hidden.getName());
        }
        String hiddenBody = createRejected(hidden.getId());
        assertThat(hiddenBody).isEqualTo(createRejected(missing));
        assertThat(requestRepository.count()).isEqualTo(requestsBefore);
        assertThat(membershipRepository.count()).isEqualTo(membershipsBefore);
    }

    @Test
    @DisplayName("AC-7: 公開村の旧meと非公開村の現役村人・審査者一覧を維持する")
    void 旧本人一覧と審査者の契約を維持する() throws Exception {
        VillageEntity village = village(VillageVisibility.PUBLIC);
        request(village, ACTOR, VillageSubjectType.USER, ACTOR, VillageRequestStatus.PENDING, SUBMITTED_AT);
        as(ACTOR, get("/api/v1/villages/{id}/join-requests/me", village.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        membership(village, ACTOR, VillageRole.VILLAGER);
        membership(village, REVIEWER, VillageRole.HEADMAN);
        village.setVisibility(VillageVisibility.UNLISTED);
        villageRepository.saveAndFlush(village);
        as(ACTOR, get("/api/v1/villages/{id}/join-requests/me", village.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        as(REVIEWER, get("/api/v1/villages/{id}/join-requests", village.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        as(ACTOR, get("/api/v1/villages/{id}/join-requests", village.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-8: 本人WHERE・LIMIT・安定順序をSQLで固定し村の追加SELECTを行わない")
    void 本人の履歴検索だけで一ページを取得する() throws Exception {
        for (int i = 0; i < 3; i++) {
            request(village(VillageVisibility.UNLISTED), ACTOR, VillageSubjectType.USER, ACTOR,
                    VillageRequestStatus.PENDING, SUBMITTED_AT.plusMinutes(i));
        }
        entityManager.flush();
        entityManager.clear();
        SqlIntentCounter.reset();
        as(ACTOR, get(HISTORY_PATH).param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.total").value(3));
        List<String> sql = SqlIntentCounter.capturedSqls().stream()
                .map(statement -> statement.toLowerCase(Locale.ROOT)).toList();
        assertThat(sql).as("既存StatementInspectorが実際にSQLを捕捉している").isNotEmpty();
        assertThat(sql).filteredOn(statement -> statement.contains("from village_join_requests"))
                .hasSize(2).allMatch(statement -> statement.matches(
                        "(?s).*where .*requester_user_id\\s*=\\s*\\?.*"));
        assertThat(sql).anyMatch(statement -> statement.matches(
                "(?s).*order by .*created_at desc,.*id desc.*limit.*"));
        assertThat(sql).noneMatch(statement -> statement.matches(
                "(?s).*(from|join) (villages|village_memberships|teams|organizations)\\b.*"));
    }

    private ResultActions as(Long actor, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + tokenService.issueAccessToken(actor, List.of())));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String createRejected(UUID villageId) throws Exception {
        return as(OTHER, post("/api/v1/villages/{id}/join-requests", villageId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectType\":\"USER\",\"subjectId\":" + OTHER + "}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private VillageEntity village(VillageVisibility visibility) {
        return villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("history-" + UUID.randomUUID()).name("秘密の村")
                .type(VillageType.COMMUNITY).joinPolicy(VillageJoinPolicy.APPROVAL)
                .visibility(visibility).memberCountCache(0L).createdByUserId(REVIEWER).build());
    }

    private VillageJoinRequestEntity request(VillageEntity village, Long requester,
            VillageSubjectType subject, Long subjectId, VillageRequestStatus status, LocalDateTime createdAt) {
        return requestRepository.saveAndFlush(VillageJoinRequestEntity.builder()
                .villageId(village.getId()).requesterUserId(requester).subjectType(subject).subjectId(subjectId)
                .status(status).message("本人の申請").createdAt(createdAt).build());
    }

    private void membership(VillageEntity village, Long actor, VillageRole role) {
        membershipRepository.saveAndFlush(VillageMembershipEntity.builder()
                .villageId(village.getId()).subjectType(VillageSubjectType.USER).subjectId(actor)
                .role(role).build());
    }
}
