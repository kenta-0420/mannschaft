package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.gdpr.service.GdprPurgeRetryService;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardConsumer;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.ranch.service.RanchOwnerActionFacade;
import com.mannschaft.app.ranch.service.RanchSelfFacade;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ranch own-domain消去は本人11表だけを空にし、他人の卵/枠を保持する。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchPurgeServiceIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserService lifecycle;
    @Autowired private UserRewardDeliveryGuard deliveryGuard;
    @Autowired private RanchRewardConsumer consumer;
    @Autowired private RanchOwnerActionFacade actions;
    @Autowired private RanchSelfFacade self;
    @Autowired private TransactionTemplate transaction;
    @Autowired private AccountPurgeCompletionStatusRepository completions;
    @Autowired private GdprPurgeRetryService retry;
    @Autowired private ApplicationEventPublisher events;
    @Autowired @Qualifier("purge-pool") private Executor purgeExecutor;

    @Autowired private UserRepository users;
    @Autowired private RanchPurgeService purge;
    @Autowired private RanchOperationalControlRepository controls;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchRoomPlacementRepository slots;
    @MockitoSpyBean private JdbcTemplate jdbc;
    private Long me;
    private Long other;
    private final List<RanchGdprFixture.FixtureIds> masterRows = new ArrayList<>();
    private static final String[] OWNED_TABLES = {
            "ranch_point_ledger", "ranch_reward_decisions", "ranch_week_budgets",
            "ranch_affinity_units", "ranch_care_week_budgets", "ranch_room_placements",
            "ranch_collectible_inventory", "ranch_commands",
            "ranch_participation_periods", "ranch_dinosaurs", "ranch_owners"};

    private int count(String table, Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
                Integer.class, userId);
    }

    @BeforeEach
    void createSyntheticUsers() {
        RanchTestFixture.operationalControl(controls);
        masterRows.clear();
        // 外部Redisだけを既共通基底のmockにし、退会処理とDB guardは実Beanを使う。
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(values);
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        other = users.saveAndFlush(RanchTestFixture.user()).getId();
    }

    @AfterEach
    void removeOnlyOwnFixtureRows() {
        if (me != null) purge.purgeUser(me);
        if (other != null) purge.purgeUser(other);
        for (Long userId : new Long[] {me, other}) {
            if (userId != null) completions.findByUserIdAndDomainName(userId, "ranch")
                    .ifPresent(completions::delete);
        }
        for (var row : masterRows) {
            jdbc.update("DELETE FROM ranch_reward_policies WHERE id = UUID_TO_BIN(?)",
                    row.policyId().toString());
            jdbc.update("DELETE FROM ranch_collectible_catalog WHERE collectible_key = ?",
                    row.collectibleKey());
        }
    }

    private void enroll(Long userId) throws Exception {
        mvc.perform(post("/api/v1/me/ranch").with(user(userId.toString()))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void deletesOwnRowsInOneTransactionAndRetryIsIdempotent() throws Exception {
        enroll(me);
        enroll(other);
        for (Long userId : new Long[] {me, other}) {
            masterRows.add(RanchGdprFixture.populate(jdbc, userId,
                    owners.findByUserId(userId).orElseThrow().getId(),
                    dinosaurs.findByUserId(userId).orElseThrow().getId()));
        }
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3);
        for (String table : OWNED_TABLES) {
            assertThat(count(table, me)).as("before me " + table).isPositive();
            assertThat(count(table, other)).as("before other " + table).isPositive();
        }
        purge.purgeUser(me);
        purge.purgeUser(me);
        assertThat(owners.findByUserId(me)).isEmpty();
        assertThat(dinosaurs.findByUserId(me)).isEmpty();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).isEmpty();
        for (String table : OWNED_TABLES) {
            assertThat(count(table, me)).as("after me " + table).isZero();
            assertThat(count(table, other)).as("after other " + table).isPositive();
        }
        assertThat(owners.findByUserId(other)).isPresent();
        assertThat(dinosaurs.findByUserId(other)).isPresent();
        assertThat(slots.findByUserIdOrderBySlotKey(other)).hasSize(3);
    }
    @Test
    void downstreamDeleteFailureRollsBackEarlierLedgerDeletion() throws Exception {
        enroll(me);
        masterRows.add(RanchGdprFixture.populate(jdbc, me,
                owners.findByUserId(me).orElseThrow().getId(),
                dinosaurs.findByUserId(me).orElseThrow().getId()));
        var before = new LinkedHashMap<String, Integer>();
        for (String table : OWNED_TABLES) {
            int rows = count(table, me);
            assertThat(rows).as("before rollback " + table).isPositive();
            before.put(table, rows);
        }
        var injected = new DataAccessResourceFailureException("自分の後段 DELETE の故障 fixture");
        // spy は自分の二番目の DELETE だけを差し替え、他の JDBC は実 DB に通す。
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(count("ranch_point_ledger", me)).as("故障直前の実 ledger DELETE").isZero();
            throw injected;
        }).when(jdbc).update("DELETE FROM ranch_reward_decisions WHERE user_id = ?", me);
        try {
            assertThatThrownBy(() -> purge.purgeUser(me)).isSameAs(injected);
            verify(jdbc).update("DELETE FROM ranch_point_ledger WHERE user_id = ?", me);
            for (String table : OWNED_TABLES) {
                assertThat(count(table, me)).as("rollback " + table).isEqualTo(before.get(table));
            }
        } finally {
            // 自動 reset の時機を待たず、既存 @AfterEach の実 purge より前に故障を外す。
            reset(jdbc);
        }
    }


    /** 現在のauth状態が正本であり、通知順序で元の参加状態や期間を変更しない。 */
    @ParameterizedTest
    @EnumSource(value = ParticipationStatus.class, names = {"ACTIVE", "PAUSED"})
    void 退会取消と再申請は元参加状態を保ち旧配送で復帰しない(ParticipationStatus original) throws Exception {
        enroll(me);
        if (original == ParticipationStatus.PAUSED) {
            actions.pause(me, UUID.randomUUID(), new RanchVersionRequest(ownerVersion()));
        }
        var before = ownRows(me);
        var oldFact = fact(me);
        lifecycle.requestWithdrawal(me, new RequestWithdrawalRequest(null));
        var first = deliveryGuard.withLockedDeliveryUser(me, state -> state);
        assertThat(first.lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.WITHDRAWAL);
        assertThat(first.withdrawalAttemptId()).isNotNull();
        assertDeferred(oldFact);
        lifecycle.cancelWithdrawal(me);
        var cancelled = deliveryGuard.withLockedDeliveryUser(me, state -> state);
        assertThat(cancelled.lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.ACTIVE);
        assertThat(cancelled.withdrawalAttemptId()).isEqualTo(first.withdrawalAttemptId());
        assertThat(owners.findByUserId(me).orElseThrow().getStatus()).isEqualTo(original);
        assertThat(ownRows(me)).usingRecursiveComparison().isEqualTo(before);
        lifecycle.requestWithdrawal(me, new RequestWithdrawalRequest(null));
        var renewed = deliveryGuard.withLockedDeliveryUser(me, state -> state);
        assertThat(renewed.lifecycle()).isEqualTo(DeliveryUserState.Lifecycle.WITHDRAWAL);
        assertThat(renewed.withdrawalAttemptId()).isNotEqualTo(first.withdrawalAttemptId());
        // 再申請後の新factより旧factを後に配送しても、現在の退会状態で双方を保留する。
        assertDeferred(fact(me));
        assertDeferred(oldFact);
        UUID currentAttemptId = deliveryGuard.withLockedDeliveryUser(me, state -> state.withdrawalAttemptId());
        assertThat(currentAttemptId).isEqualTo(renewed.withdrawalAttemptId());
        assertThat(owners.findByUserId(me).orElseThrow().getStatus()).isEqualTo(original);
        assertThat(ownRows(me)).usingRecursiveComparison().isEqualTo(before);
    }

    /** 消去前から待機する配送をmarkerとcleanupの後に解放し、新規作成を拒否する。 */
    @Test
    void PURGING確定後は命令と後着配送で本人行を再作成しない() throws Exception {
        populateBoth();
        var otherBefore = ownRows(other);
        var oldFact = fact(me);
        var queued = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> {
                queued.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("後着配送の待機期限切れ");
                return consumer.consume(oldFact);
            });
            assertThat(queued.await(10, TimeUnit.SECONDS)).isTrue();
            markPurging();
            DeliveryUserState.Lifecycle currentLifecycle =
                    deliveryGuard.withLockedDeliveryUser(me, state -> state.lifecycle());
            assertThat(currentLifecycle).isEqualTo(DeliveryUserState.Lifecycle.PURGING);
            assertCommandDenied(() -> actions.pause(me, UUID.randomUUID(), new RanchVersionRequest(ownerVersion())));
            assertDeleted(consumer.consume(fact(me)));
            purge.purgeUser(me);
            release.countDown();
            assertDeleted(pending.get(10, TimeUnit.SECONDS));
            assertDeleted(consumer.consume(oldFact));
            assertCommandDenied(() -> self.enroll(me, UUID.randomUUID()));
            for (String table : OWNED_TABLES) assertThat(count(table, me)).as(table).isZero();
            assertThat(ownRows(other)).usingRecursiveComparison().isEqualTo(otherBefore);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** care/shop/配送停止下でも実AFTER_COMMIT消去は走り、障害後は固定domain retryで回復する。 */
    @Test
    void OFF下の消去障害は完了にせず実retryで本人だけ消す() throws Exception {
        populateBoth();
        markPurging();
        var control = controls.findById(1).orElseThrow();
        assertThat(control.isCareEnabled()).isFalse();
        assertThat(control.isShopEnabled()).isFalse();
        assertThat(control.isDeliveryPaused()).isTrue();
        var completion = new AccountPurgeCompletionStatusEntity();
        completion.setUserId(me);
        completion.setEmailHash("0".repeat(64));
        completion.setDomainName("ranch");
        completion.setStatus("PENDING");
        completion.setAttemptedAt(LocalDateTime.now());
        completions.saveAndFlush(completion);
        var before = ownRows(me);
        var otherBefore = ownRows(other);
        assertThat(purgeExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        var pool = (ThreadPoolTaskExecutor) purgeExecutor;
        var faultReached = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(count("ranch_point_ledger", me)).isZero();
            faultReached.countDown();
            throw new DataAccessResourceFailureException("本人後段削除の故障fixture");
        }).when(jdbc).update("DELETE FROM ranch_reward_decisions WHERE user_id = ?", me);
        try {
            transaction.executeWithoutResult(tx -> events.publishEvent(new AccountPurgedEvent(me, "0".repeat(64))));
            assertThat(faultReached.await(10, TimeUnit.SECONDS)).as("停止中にも実消去listenerへ到達").isTrue();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(pool.getActiveCount()).isZero());
            assertThat(ownRows(me)).usingRecursiveComparison().isEqualTo(before);
            var failed = completions.findByUserIdAndDomainName(me, "ranch").orElseThrow();
            assertThat(failed.getStatus()).isEqualTo("PENDING");
            assertThat(failed.getCompletedAt()).isNull();
        } finally {
            reset(jdbc);
        }
        var recovered = retry.retryDomainPurge(me, "ranch");
        assertThat(recovered.succeeded()).isTrue();
        assertThat(recovered.newStatus()).isEqualTo("SUCCESS");
        assertThat(recovered.retryCount()).isEqualTo(1);
        for (String table : OWNED_TABLES) assertThat(count(table, me)).as(table).isZero();
        var saved = completions.findByUserIdAndDomainName(me, "ranch").orElseThrow();
        assertThat(saved.getCompletedAt()).isNotNull();
        var repeated = retry.retryDomainPurge(me, "ranch");
        assertThat(repeated.succeeded()).isTrue();
        assertThat(repeated.retryCount()).isEqualTo(1);
        assertThat(completions.findByUserIdAndDomainName(me, "ranch").orElseThrow().getCompletedAt())
                .isEqualTo(saved.getCompletedAt());
        for (String table : OWNED_TABLES) assertThat(count(table, me)).as(table).isZero();
        assertThat(ownRows(other)).usingRecursiveComparison().isEqualTo(otherBefore);
    }

    private void populateBoth() throws Exception {
        // 消去専用の全表fixtureを追加する前に、両本人の通常参加を完了する。
        for (Long userId : new Long[] {me, other}) {
            enroll(userId);
        }
        for (Long userId : new Long[] {me, other}) {
            masterRows.add(RanchGdprFixture.populate(jdbc, userId,
                    owners.findByUserId(userId).orElseThrow().getId(),
                    dinosaurs.findByUserId(userId).orElseThrow().getId()));
            for (String table : OWNED_TABLES) assertThat(count(table, userId)).as(table).isPositive();
        }
    }

    private Map<String, List<Map<String, Object>>> ownRows(Long userId) {
        var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : OWNED_TABLES) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE user_id = ? ORDER BY id", userId));
        }
        return rows;
    }

    private String ownerVersion() {
        return Long.toString(owners.findByUserId(me).orElseThrow().getVersion());
    }

    private void markPurging() {
        lifecycle.requestWithdrawal(me, new RequestWithdrawalRequest(null));
        transaction.executeWithoutResult(tx -> users.markPurgeStarted(me));
    }

    private void assertCommandDenied(Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode().getCode()).isEqualTo("AUTHOPERATION_002"));
    }

    private void assertDeferred(RanchRewardEnvelope envelope) {
        var outcome = consumer.consume(envelope);
        assertThat(outcome.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.DEFER);
        assertThat(outcome.decisionId()).isNull();
        assertThat(outcome.awardedPoints()).isZero();
    }

    private void assertDeleted(RanchRewardDeliveryOutcome outcome) {
        assertThat(outcome.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.ACCOUNT_DELETED);
        assertThat(outcome.decisionId()).isNull();
        assertThat(outcome.awardedPoints()).isZero();
    }

    private RanchRewardEnvelope fact(Long userId) {
        var occurred = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var week = occurred.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new RanchRewardEnvelope(UUID.randomUUID(), 1,
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                RanchRewardEnvelope.IdType.UUID, UUID.randomUUID().toString(),
                RanchRewardEnvelope.ScopeType.PERSONAL, null, null,
                RanchRewardEnvelope.ActorKind.USER, userId, null, userId, userId,
                occurred, RanchRewardEnvelope.Origin.PERSONAL_COMPLETION,
                new RanchRewardEnvelope.PersonalRecall(UUID.randomUUID(), 4, week, true));
    }

}
