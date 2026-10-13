package com.mannschaft.app.committee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * CMP-260820-1422: 既済の議事録子ID秘匿を実JWT/filter/MySQLの完全本文契約で固定する。
 * productionは不在/他委員会/非委員会scopeを既にNOT_FOUNDへ畳むためGREEN補強である。
 * 共通MOCKを継承しRepository fixtureを独立commitする。本番FlywayのFK適用証明ではない。
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP1422 委員会議事録の子ID完全本文契約")
class CommitteeMinutesScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private CommitteeRepository committees;
    @Autowired private CommitteeMemberRepository members;
    @Autowired private ActivityTemplateRepository templates;
    @Autowired private ActivityResultRepository records;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> committeeIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> templateIds = new ArrayList<>();
    private final List<Long> recordIds = new ArrayList<>();
    private final Map<String, Long> actors = new LinkedHashMap<>();
    private Long org;
    private Long a;
    private Long b;
    private Long own;
    private final Map<String, List<Long>> foreign = new LinkedHashMap<>();

    @BeforeEach
    void 実在親と委員ロールと記録をRepositoryで準備する() {
        var values = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        tx(() -> {
            for (String label : List.of("chair", "vice", "member", "secretary", "chairB", "former", "outsider")) {
                Long id = users.save(UserEntity.builder().email("minutes-" + UUID.randomUUID() + "@example.com")
                        .lastName("契約").firstName("試験").displayName("議事録契約").isSearchable(false)
                        .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
                userIds.add(id); actors.put(label, id);
            }
            org = organizations.save(OrganizationEntity.builder()
                    .slug("minutes-" + UUID.randomUUID().toString().substring(0, 20)).name("議事録契約組織")
                    .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
            orgIds.add(org);
            a = committee("議事録契約A"); b = committee("議事録契約B");
            member(a, "chair", CommitteeRole.CHAIR, false);
            member(a, "vice", CommitteeRole.VICE_CHAIR, false);
            member(a, "member", CommitteeRole.MEMBER, false);
            member(a, "secretary", CommitteeRole.SECRETARY, false);
            member(b, "chairB", CommitteeRole.CHAIR, false);
            member(a, "former", CommitteeRole.CHAIR, true);
            own = record(ActivityScopeType.COMMITTEE, a, "DRAFT");
            for (String state : List.of("DRAFT", "CONFIRMED")) {
                foreign.put(state, List.of(record(ActivityScopeType.COMMITTEE, b, state),
                        record(ActivityScopeType.ORGANIZATION, org, state)));
            }
            assertThat(records.existsById(MISSING)).isFalse();
            assertThat(committees.existsById(MISSING)).isFalse();
            return null;
        });
    }

    @AfterEach
    void 所有行だけ削除して共有認証状態を復元する() {
        try {
            tx(() -> {
                records.deleteAllById(recordIds);
                em.flush();
                templates.deleteAllById(templateIds);
                members.deleteAllById(memberIds);
                em.flush();
                committees.deleteAllById(committeeIds);
                em.flush();
                organizations.deleteAllById(orgIds);
                em.flush();
                users.deleteAllById(userIds);
                return null;
            });
        } finally {
            try { reset(redisTemplate); } finally { SecurityContextHolder.clearContext(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "CONFIRMED"})
    void 他委員会と非委員会scopeは状態によらず不在と完全同一でDB不変(String state) throws Exception {
        for (String label : List.of("chair", "vice")) {
            Map<String, String> before = snapshot(null);
            try {
                MvcResult absent = http(actors.get(label), a, MISSING);
                assertError(absent, 404, "COMMITTEE_NOT_FOUND", "委員会が見つかりません");
                for (Long id : foreign.get(state)) {
                    MvcResult denied = http(actors.get(label), a, id);
                    assertError(denied, 404, "COMMITTEE_NOT_FOUND", "委員会が見つかりません");
                    assertThat(denied.getResponse().getContentType()).isEqualTo(absent.getResponse().getContentType());
                    assertThat(json(denied)).isEqualTo(json(absent));
                }
            } finally { assertThat(snapshot(null)).isEqualTo(before); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chair", "vice"})
    void 正当役職だけ確定し再確定409を維持する(String label) throws Exception {
        Map<String, String> otherBefore = snapshot(own);
        Map<String, String> recordBefore = recordColumns(own);
        Instant start = Instant.now();
        MvcResult success = http(actors.get(label), a, own);
        assertThat(success.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = json(success).path("data");
        assertThat(data.path("activityRecordId").asLong()).isEqualTo(own);
        JsonNode fields = mapper.readTree(data.path("fieldValues").asText());
        assertThat(fields.path("_meta").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(fields.path("_meta").path("confirmed_by").asLong()).isEqualTo(actors.get(label));
        assertThat(Instant.parse(fields.path("_meta").path("confirmed_at").asText())).isBetween(start, Instant.now());
        assertThat(fields).isEqualTo(mapper.readTree(mapper.writeValueAsString(Map.of("memo", "原記録", "_meta", Map.of(
                "status", "CONFIRMED", "confirmed_by", actors.get(label),
                "confirmed_at", fields.path("_meta").path("confirmed_at").asText())))));
        JsonNode stored = tx(() -> {
            try { return mapper.readTree(records.findById(own).orElseThrow().getFieldValues()); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        });
        assertThat(stored).isEqualTo(fields);
        Map<String, String> recordAfter = recordColumns(own);
        assertThat(Long.parseLong(recordAfter.get("version"))).isEqualTo(Long.parseLong(recordBefore.get("version")) + 1);
        // 成功時も意図したJSON、更新日時、楽観version以外の全列を変えてはならない。
        for (String column : List.of("field_values", "updated_at", "version")) {
            recordBefore.remove(column); recordAfter.remove(column);
        }
        assertThat(recordAfter).isEqualTo(recordBefore);
        assertThat(snapshot(own)).isEqualTo(otherBefore);
        Map<String, String> confirmed = snapshot(null);
        try {
            assertError(http(actors.get(label), a, own), 409,
                    "COMMITTEE_MINUTES_ALREADY_CONFIRMED", "議事録は既に確定済みです");
        } finally { assertThat(snapshot(null)).isEqualTo(confirmed); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"member", "secretary", "chairB", "former", "outsider"})
    void 権限不足は子の実在scope状態によらず先行403でDB不変(String label) throws Exception {
        Map<String, String> before = snapshot(null);
        try {
            MvcResult absent = http(actors.get(label), a, MISSING);
            assertError(absent, 403, "COMMON_002", "この操作を行う権限がありません");
            List<Long> ids = new ArrayList<>(List.of(own));
            foreign.values().forEach(ids::addAll);
            for (Long id : ids) {
                MvcResult denied = http(actors.get(label), a, id);
                assertError(denied, 403, "COMMON_002", "この操作を行う権限がありません");
                assertThat(denied.getResponse().getContentType()).isEqualTo(absent.getResponse().getContentType());
                assertThat(json(denied)).isEqualTo(json(absent));
            }
        } finally { assertThat(snapshot(null)).isEqualTo(before); }
    }

    @Test
    void 未認証は実filterで401かつDB不変() throws Exception {
        Map<String, String> before = snapshot(null);
        try { assertThat(http(null, a, own).getResponse().getStatus()).isEqualTo(401); }
        finally { assertThat(snapshot(null)).isEqualTo(before); }
    }

    @Test
    void 不正Long型は400かつDB不変() throws Exception {
        Map<String, String> before = snapshot(null);
        try {
            assertThat(http(actors.get("chair"), a, "invalid").getResponse().getStatus()).isEqualTo(400);
            assertThat(http(actors.get("chair"), "invalid", own).getResponse().getStatus()).isEqualTo(400);
        } finally { assertThat(snapshot(null)).isEqualTo(before); }
    }

    @Test
    void 有効Long不在境界と親不在は404かつDB不変() throws Exception {
        Map<String, String> before = snapshot(null);
        try {
            for (Long id : List.of(0L, -1L, MISSING)) {
                assertError(http(actors.get("chair"), a, id), 404, "COMMITTEE_NOT_FOUND", "委員会が見つかりません");
            }
            assertError(http(actors.get("chair"), MISSING, own), 404, "COMMITTEE_NOT_FOUND", "委員会が見つかりません");
        } finally { assertThat(snapshot(null)).isEqualTo(before); }
    }

    private MvcResult http(Long actor, Object path, Object child) throws Exception {
        var request = patch("/api/v1/committees/{id}/activity-records/{recordId}/confirm", path, child)
                .header("Accept-Language", "ja");
        if (actor != null) { request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER"))); }
        return mvc.perform(request).andReturn();
    }

    private void assertError(MvcResult result, int status, String code, String message) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(json(result)).isEqualTo(mapper.valueToTree(Map.of("error", Map.of(
                "code", code, "message", message, "fieldErrors", List.of()))));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, String> snapshot(Long excludedRecord) {
        return tx(() -> Map.of("records", rows("activity_results", recordIds.stream()
                        .filter(id -> !id.equals(excludedRecord)).toList()),
                "templates", rows("activity_templates", templateIds), "members", rows("committee_members", memberIds),
                "committees", rows("committees", committeeIds), "organizations", rows("organizations", orgIds),
                "users", rows("users", userIds)));
    }

    private Map<String, String> recordColumns(Long id) {
        return tx(() -> {
            Map<String, String> result = new LinkedHashMap<>();
            jdbc.queryForMap("SELECT * FROM activity_results WHERE id = ?", id).forEach((column, value) ->
                    result.put(column, value instanceof byte[] bytes ? Arrays.toString(bytes) : String.valueOf(value)));
            return result;
        });
    }

    private String rows(String table, List<Long> ids) {
        return Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " WHERE id IN (:ids) ORDER BY id")
                .setParameter("ids", ids).getResultList().toArray());
    }

    private <T> T tx(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private Long committee(String name) {
        Long id = committees.save(CommitteeEntity.builder().organizationId(org).name(name)
                .status(CommitteeStatus.ACTIVE).createdBy(actors.get("chair")).build()).getId();
        committeeIds.add(id); return id;
    }

    private void member(Long committee, String label, CommitteeRole role, boolean former) {
        CommitteeMemberEntity row = CommitteeMemberEntity.builder().committeeId(committee).userId(actors.get(label))
                .role(role).joinedAt(LocalDateTime.now().minusDays(1)).invitedBy(actors.get("chair")).build();
        if (former) { row.leave(); }
        memberIds.add(members.save(row).getId());
    }

    private Long record(ActivityScopeType scope, Long scopeId, String state) {
        Long template = templates.save(ActivityTemplateEntity.builder().scopeType(scope).scopeId(scopeId)
                .name("議事録契約").createdBy(actors.get("chair")).build()).getId();
        templateIds.add(template);
        Long id = records.save(ActivityResultEntity.builder().scopeType(scope).scopeId(scopeId).templateId(template)
                .title("議事録契約").activityDate(LocalDate.now()).createdBy(actors.get("chair"))
                .fieldValues("{\"_meta\":{\"status\":\"" + state + "\"},\"memo\":\"原記録\"}").build()).getId();
        recordIds.add(id); return id;
    }
}
