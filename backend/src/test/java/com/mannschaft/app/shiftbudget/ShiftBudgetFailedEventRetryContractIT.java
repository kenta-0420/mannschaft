package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.shiftbudget.batch.ShiftBudgetRetryBatchJob;
import com.mannschaft.app.shiftbudget.dto.FailedEventResponse;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetRetryExecutor;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260910-1606: 実認可・MySQL・Spring proxyを通す再試行の保存契約。
 * テスト自体はTXで包まず、既定isolationを変えずにコミット後の値を比較する。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ShiftBudgetFailedEventRetryContractIT extends AbstractMySqlIntegrationTest {
    private static final String DIAGNOSIS = "初回の診断情報を保持する";
    private static final LocalDateTime OLD_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired private ShiftBudgetFailedEventService service;
    @Autowired private ShiftBudgetRetryExecutor executor;
    @Autowired private ShiftBudgetFailedEventRepository repository;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private FeatureFlagRepository flags;
    @Autowired private CacheManager caches;
    @Autowired private ShiftBudgetProperties properties;
    @Autowired private BudgetConfigRepository configs;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @PersistenceContext private EntityManager em;

    private final List<Long> events = new ArrayList<>();
    private Long org;
    private Long actor;
    private Long member;
    private Long outsider;
    private boolean oldEnabled;

    @BeforeEach
    void 準備() {
        oldEnabled = properties.isEnabled();
        properties.setEnabled(true);
        FeatureFlagTestSupport.enable(flags, caches, "FEATURE_SHIFT_ENABLED");
        transactions.executeWithoutResult(tx -> {
            OrganizationEntity organization = OrganizationEntity.builder()
                    .name("再試行契約").slug("r-" + UUID.randomUUID().toString().substring(0, 20))
                    .orgType(OrganizationEntity.OrgType.OTHER)
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .supporterEnabled(true).build();
            organizations.save(organization);
            org = organization.getId();
            actor = createUser();
            member = createUser();
            outsider = createUser();
            MembershipTestHelper.insertUserRole(em, actor, "SYSTEM_ADMIN", null, null);
            MembershipTestHelper.insertMembership(em, member, ScopeType.ORGANIZATION, org, RoleKind.MEMBER);
        });
        authenticate(actor);
    }

    @AfterEach
    void 後始末() {
        SecurityContextHolder.clearContext();
        repository.deleteAllById(events);
        properties.setEnabled(oldEnabled);
    }

    @ParameterizedTest
    @EnumSource(value = ShiftBudgetFailedEventType.class, names = {"CONSUMPTION_RECORD", "CONSUMPTION_CANCEL"})
    void 非対応の手動再試行は409で全列不変(ShiftBudgetFailedEventType type) throws Exception {
        Long id = event(type, ShiftBudgetFailedEventStatus.PENDING, "{}", DIAGNOSIS);
        Map<String, Object> before = row(id);
        mvc.perform(post("/api/v1/shift-budget/failed-events/{id}/retry", id)
                        .with(user(actor.toString())).with(csrf()).header("X-Organization-Id", org))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_031"));
        assertThat(row(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = ShiftBudgetFailedEventStatus.class, names = {"SUCCEEDED", "MANUAL_RESOLVED"})
    void 終端は拒否し元行不変(ShiftBudgetFailedEventStatus state) {
        Long id = event(ShiftBudgetFailedEventType.THRESHOLD_ALERT, state, "{}", DIAGNOSIS);
        Map<String, Object> before = row(id);
        assertRejected(id, "SHIFT_BUDGET_031");
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void 認可なしと非所属と他組織では元行不変() {
        Long id = event(ShiftBudgetFailedEventType.THRESHOLD_ALERT, ShiftBudgetFailedEventStatus.PENDING, "{}", DIAGNOSIS);
        Map<String, Object> before = row(id);
        for (Long userId : List.of(member, outsider)) {
            authenticate(userId);
            assertRejected(id, "SHIFT_BUDGET_027");
        }
        authenticate(actor);
        assertThatThrownBy(() -> service.retry(org + 1000000, id)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ShiftBudgetErrorCode.FAILED_EVENT_NOT_FOUND));
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void 組織機能無効は元行不変で空一覧は正常() {
        assertThat(service.list(org, null, 0, 20)).isEmpty();
        Long id = event(ShiftBudgetFailedEventType.THRESHOLD_ALERT, ShiftBudgetFailedEventStatus.PENDING, "{}", DIAGNOSIS);
        Map<String, Object> before = row(id);
        configs.save(BudgetConfigEntity.builder().scopeType("ORGANIZATION").scopeId(org).shiftBudgetEnabled(false).build());
        assertThatThrownBy(() -> service.retry(org, id)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ShiftBudgetErrorCode.FEATURE_DISABLED));
        assertThat(row(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = ShiftBudgetFailedEventType.class, names = {"THRESHOLD_ALERT", "WORKFLOW_START", "NOTIFICATION_SEND"})
    void 対応種の再失敗でも初回診断を保持し試行は保存する(ShiftBudgetFailedEventType type) {
        Long id = event(type, ShiftBudgetFailedEventStatus.PENDING, "{}", DIAGNOSIS);
        // THRESHOLD_ALERTはsource=null、他2種はpayload不足で実処理が失敗する。
        jdbc.update("UPDATE shift_budget_failed_events SET source_id = NULL WHERE id = ?", id);
        FailedEventResponse response = service.retry(org, id);
        assertThat(response.errorMessage()).isEqualTo(DIAGNOSIS);
        assertThat(response.retryCount()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(row(id).get("error_message")).isEqualTo(DIAGNOSIS);
        assertThat(response).isEqualTo(FailedEventResponse.from(repository.findById(id).orElseThrow()));
        FailedEventResponse second = service.retry(org, id);
        assertThat(second.retryCount()).isEqualTo(2);
        assertThat(second.errorMessage()).isEqualTo(DIAGNOSIS);
        assertThat(second.lastRetriedAt()).isAfterOrEqualTo(response.lastRetriedAt());
        assertThat(second).isEqualTo(FailedEventResponse.from(repository.findById(id).orElseThrow()));
        assertThat(service.retry(org, id).status()).isEqualTo("EXHAUSTED");
        FailedEventResponse manual = service.retry(org, id);
        assertThat(manual.retryCount()).isEqualTo(4);
        assertThat(manual.errorMessage()).isEqualTo(DIAGNOSIS);
    }

    @Test
    void 外側失敗でrollbackしても内側の再試行結果を戻さない() {
        Long id = event(ShiftBudgetFailedEventType.THRESHOLD_ALERT, ShiftBudgetFailedEventStatus.PENDING, "{}", DIAGNOSIS);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            service.retry(org, id);
            throw new IllegalStateException("契約試験で外側だけをrollbackする");
        })).isInstanceOf(IllegalStateException.class);
        FailedEventResponse committed = FailedEventResponse.from(repository.findById(id).orElseThrow());
        assertThat(committed.status()).isEqualTo("SUCCEEDED");
        assertThat(committed.retryCount()).isEqualTo(1);
        assertThat(committed.lastRetriedAt()).isNotNull();
        assertThat(committed.errorMessage()).isEqualTo(DIAGNOSIS);
        assertThat(committed.updatedAt()).isAfter(OLD_TIME);
        assertThat(service.list(org, null, 0, 20)).contains(committed);
        Map<String, Object> before = row(id);
        assertRejected(id, "SHIFT_BUDGET_031");
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    void 対応種EXHAUSTEDは手動再試行でき時刻は直後応答と次GETとDBで一致する() {
        Long id = event(ShiftBudgetFailedEventType.THRESHOLD_ALERT, ShiftBudgetFailedEventStatus.EXHAUSTED, "{}", DIAGNOSIS);
        Integer isolation = jdbc.execute((java.sql.Connection connection) -> connection.getTransactionIsolation());
        assertThat(isolation).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        FailedEventResponse response = service.retry(org, id);
        FailedEventResponse next = service.list(org, null, 0, 20).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        LocalDateTime persisted = jdbc.queryForObject("SELECT updated_at FROM shift_budget_failed_events WHERE id = ?", LocalDateTime.class, id);
        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.errorMessage()).isEqualTo(DIAGNOSIS);
        // HibernateはUTC格納、APIのLocalDateTimeはテスト規約どおりJST。時刻の意味を揃えて比較する。
        LocalDateTime persistedJst = persisted.atOffset(java.time.ZoneOffset.UTC)
                .atZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo")).toLocalDateTime();
        assertThat(response.updatedAt()).isAfter(OLD_TIME).isEqualTo(persistedJst).isEqualTo(next.updatedAt());
    }

    @ParameterizedTest
    @EnumSource(value = ShiftBudgetFailedEventType.class, names = {"THRESHOLD_ALERT", "WORKFLOW_START", "NOTIFICATION_SEND"})
    void 対応3種の成功結果と初回診断が保存される(ShiftBudgetFailedEventType type) {
        String payload = switch (type) {
            case WORKFLOW_START -> "{\"allocation_id\":999999999}";
            case NOTIFICATION_SEND -> "{\"user_ids\":[" + actor
                    + "],\"title\":\"契約\",\"body\":\"再試行\",\"scope_id\":" + org + "}";
            default -> "{}";
        };
        Long id = event(type, ShiftBudgetFailedEventStatus.PENDING, payload, DIAGNOSIS);
        FailedEventResponse response = service.retry(org, id);
        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.retryCount()).isEqualTo(1);
        assertThat(response.errorMessage()).isEqualTo(DIAGNOSIS);
        assertThat(response.lastRetriedAt()).isNotNull();
        assertThat(response).isEqualTo(FailedEventResponse.from(repository.findById(id).orElseThrow()));
    }

    @Test
    void 非対応バッチは診断と試行情報を保ちEXHAUSTEDにし後続を処理する() {
        for (ShiftBudgetFailedEventType type : List.of(ShiftBudgetFailedEventType.CONSUMPTION_RECORD, ShiftBudgetFailedEventType.CONSUMPTION_CANCEL)) {
            Long id = event(type, ShiftBudgetFailedEventStatus.PENDING, "{}", DIAGNOSIS);
            Map<String, Object> before = row(id);
            new ShiftBudgetRetryBatchJob(repository, executor).run();
            Map<String, Object> after = row(id);
            assertThat(after.get("status")).isEqualTo("EXHAUSTED");
            for (String column : List.of("error_message", "retry_count", "last_retried_at", "payload", "source_id")) {
                assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
            }
        }
        Long supported = event(ShiftBudgetFailedEventType.WORKFLOW_START, ShiftBudgetFailedEventStatus.PENDING, "{\"allocation_id\":999999999}", DIAGNOSIS);
        new ShiftBudgetRetryBatchJob(repository, executor).run();
        assertThat(row(supported).get("status")).isEqualTo("SUCCEEDED");
    }

    private Long createUser() {
        UserEntity user = UserEntity.builder().email("retry-" + UUID.randomUUID() + "@example.test")
                .lastName("試験").firstName("利用者").displayName("再試行契約")
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        return users.save(user).getId();
    }

    private Long event(ShiftBudgetFailedEventType type, ShiftBudgetFailedEventStatus state, String payload, String diagnostic) {
        Long id = repository.saveAndFlush(ShiftBudgetFailedEventEntity.builder().organizationId(org).eventType(type)
                .sourceId(999999999L).payload(payload).errorMessage(diagnostic).retryCount(0).status(state).build()).getId();
        events.add(id);
        jdbc.update("UPDATE shift_budget_failed_events SET updated_at = ? WHERE id = ?", OLD_TIME, id);
        return id;
    }

    private Map<String, Object> row(Long id) {
        return jdbc.queryForMap("SELECT * FROM shift_budget_failed_events WHERE id = ?", id);
    }

    private void assertRejected(Long id, String code) {
        assertThatThrownBy(() -> service.retry(org, id)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }

    private void authenticate(Long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
    }
}
