package com.mannschaft.app.reflection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.reflection.service.RecallSessionOperationFacade;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.assertj.core.api.SoftAssertions;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.jdbc.JdbcUtcCalendar;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAckRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryAck;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryRequest;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.repository.RanchWeekBudgetRepository;
import com.mannschaft.app.ranch.reward.RanchRewardPolicyCodec;
import com.mannschaft.app.ranch.reward.RanchRewardPolicySnapshot;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.RanchRewardWriter;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.reflection.repository.ReflectionRanchOutboxRepository;
import com.mannschaft.app.reflection.service.ReflectionRanchOutboxAdminService;
import com.mannschaft.app.reflection.service.ReflectionRanchOutboxDeliveryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AC33のnative SQL DATEと保存済みACKを、日曜対照/月曜境界で検査する。
 * driver UTCと局所Clockの専用contextだけを閉じる。JVM UTC/JSTは別プロセスで指定し、global TZを変更しない。
 * 実Facade/queue/transport/MySQLを使うが、source ACK・Ranch consumer付与の証明にはしない。
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.data-source-properties.serverTimezone=UTC",
        "ranch.source.transport.queue-capacity=16",
        "mannschaft.ranch.development-fixtures=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RecallRewardWeekTemporalIT extends AbstractMySqlIntegrationTest {
    private static final Instant START = Instant.parse("2030-10-06T23:59:58Z");
    private static final Instant NEXT_WEEK = Instant.parse("2030-10-14T12:00:00Z");
    @Autowired private UserRepository users;
    @Autowired private ReflectionThemeRepository themes;
    @Autowired private ReflectionEntryRepository entries;
    @Autowired private RecallAttemptRepository attempts;
    @Autowired private RecallSessionOperationFacade operations;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @TestBean(name = "utcClock", methodName = "recallTemporalClock")
    private Clock clock;
    @Autowired private ReflectionRanchOutboxRepository outboxRows;
    @Autowired private ReflectionRanchOutboxDeliveryService outboxDelivery;
    @Autowired private ReflectionRanchOutboxAdminService outboxAdmin;
    @Autowired private UserOperationGuard active;
    @Autowired private RanchRewardWriter rewardWriter;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchPurgeService ranchPurge;
    @Autowired private RanchOperationalControlRepository controls;
    @Autowired private RanchRewardPolicyRepository policies;
    @Autowired private RanchOwnerRepository ranchOwners;
    @Autowired private RanchRewardDecisionRepository decisions;
    @Autowired private RanchPointLedgerRepository ledger;
    @Autowired private RanchWeekBudgetRepository budgets;
    private Long owner;

    static Clock recallTemporalClock() { return new RecallTemporalClock(); }

    @BeforeEach
    void createOnlyOwnedNativeEntry() {
        time(START);
        owner = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@recall-week.invalid")
                .lastName("週境界").firstName("本人").displayName("試験本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }

    @AfterEach
    void removeOwnedNativeAndSourceRowsAndResetClock() {
        try {
            if (owner == null) return;
            jdbc.update("DELETE FROM reflection_ranch_outboxes WHERE recipient_user_id=?", owner);
            jdbc.update("DELETE FROM reflection_ranch_witnesses WHERE recipient_user_id=?", owner);
            jdbc.update("DELETE FROM reflection_ranch_admin_commands WHERE actor_user_id=?", owner);
            jdbc.update("DELETE FROM reflection_recall_commands WHERE user_id=?", owner);
            jdbc.update("DELETE FROM recall_attempts WHERE user_id=?", owner);
            jdbc.update("DELETE FROM reflection_recall_sessions WHERE user_id=?", owner);
            jdbc.update("DELETE FROM reflection_entries WHERE user_id=?", owner);
            jdbc.update("DELETE FROM reflection_themes WHERE user_id=?", owner);
            users.deleteById(owner);
        } finally {
            time(START);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "SUNDAY_CONTROL,2030-10-06T23:59:59Z,2030-09-30",
            "MONDAY_BOUNDARY,2030-10-07T00:00:02Z,2030-10-07"
    })
    void physicalWeekAndNativeGetSurviveCompletionAndNextWeekReplay(
            String scenario, Instant completion, LocalDate expectedWeek) throws Exception {
        var theme = themes.saveAndFlush(ReflectionThemeEntity.builder()
                .userId(owner).title("UTC週境界fixture").build());
        var entry = entries.saveAndFlush(ReflectionEntryEntity.builder().userId(owner).themeId(theme.getId())
                .targetDate(LocalDate.parse("2030-10-06"))
                .structuredContent("{\"free_note\":\"開始時の本人原文\"}")
                .visibility(ReflectionVisibility.PRIVATE).build());
        var started = operations.start(owner, entry.getId(), UUID.randomUUID(), mapper.createObjectNode());
        assertThat(started.status()).isEqualTo(RecallSessionStatus.STARTED);
        assertThat(started.startedAt()).isEqualTo(START);
        assertThat(started.rewardWeek()).isNull();
        assertThat(physicalWeek(started.id())).isNull();
        assertThat(started.completedAt()).isNull();
        assertThat(started.prompts()).hasSize(1);
        var body = mapper.valueToTree(Map.of("version", started.version(), "answers", List.of(Map.of(
                "promptId", started.prompts().getFirst().id().toString(),
                "state", "ANSWERED", "text", "本人の回答")), "selfRating", "REMEMBERED"));
        UUID key = UUID.randomUUID();
        time(completion);
        var completed = operations.complete(owner, started.id(), key, body);
        var fresh = operations.get(owner, started.id());
        var checks = new SoftAssertions();
        checks.assertThat(completed.status()).isEqualTo(RecallSessionStatus.COMPLETED);
        checks.assertThat(completed.rewardWeek()).as(scenario+" complete ACK").isEqualTo(expectedWeek);
        checks.assertThat(physicalWeek(started.id())).as(scenario+" SQL CAST DATE")
                .isEqualTo(expectedWeek.toString());
        checks.assertThat(fresh.rewardWeek()).as(scenario+" fresh native GET").isEqualTo(expectedWeek);
        checks.assertThat(fresh.completedAt()).isEqualTo(completion);
        checks.assertAll();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(sourceCount(false)).isEqualTo(1);
            assertThat(sourceCount(true)).isEqualTo(1);
        });
        String savedPayload = jdbc.queryForObject(
                "SELECT payload_json FROM reflection_ranch_outboxes WHERE recipient_user_id=?",
                String.class, owner);
        var source = mapper.readValue(savedPayload, ReflectionRecallRewardPayload.class);
        assertThat(source.occurredAt()).isEqualTo(completion);
        assertThat(source.facts().completionWeek()).isEqualTo(expectedWeek);
        assertThat(source.facts().sessionId()).isEqualTo(started.id());

        time(NEXT_WEEK);
        var replay = operations.complete(owner, started.id(), key, body);
        JsonNode replayJson = mapper.valueToTree(replay);
        JsonNode completedJson = mapper.valueToTree(completed);
        assertThat(replayJson).isEqualTo(completedJson);
        var after = operations.get(owner, started.id());
        assertThat(after.startedAt()).isEqualTo(START);
        assertThat(after.completedAt()).isEqualTo(completion);
        assertThat(after.rewardWeek()).isEqualTo(expectedWeek);
        assertThat(physicalWeek(started.id())).isEqualTo(expectedWeek.toString());
        assertThat(attempts.findByEntryIdOrderByRecallDateDesc(entry.getId())).hasSize(1);
        assertThat(sourceCount(false)).isEqualTo(1);
        assertThat(sourceCount(true)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT payload_json FROM reflection_ranch_outboxes WHERE recipient_user_id=?",
                String.class, owner)).isEqualTo(savedPayload);
    }

    /** own lease行だけをfixtureで準備する。public候補選択/due判定・HTTP認可は別試験。 */
    @Test
    void 未参加の実native返値を源ACKへ渡し開始後も終端を保ち別entryだけ報酬を得る() throws Exception {
        var previousControl = controls.findById(1).orElse(null);
        UUID policyId = UUID.randomUUID();
        try {
            controls.saveAndFlush(RanchOperationalControlEntity.builder().id(1).careEnabled(false)
                    .shopEnabled(false).deliveryPaused(false).version(0).createdAt(START).updatedAt(START).build());
            publishAckPolicy(policyId);
            var original = completedSource(START.plusSeconds(1), 1);
            assertThat(ranchOwners.findByUserId(owner)).isEmpty();
            UUID token = leaseOnlyOwnPending(original);
            var actual = rewardWriter.decide(original.toEnvelope());
            assertThat(actual.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.NOT_ENROLLED);
            assertThat(actual.decisionId()).isNull(); assertThat(actual.awardedPoints()).isZero();
            assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(original.eventId(), token, dbNow(), actual))).isTrue();
            assertTerminal(original, "NOT_ENROLLED");
            assertThat(sourceCount(false)).isEqualTo(1); assertThat(sourceCount(true)).isEqualTo(1);
            assertNoRanchReward();

            Instant joinedAt = original.occurredAt().plusSeconds(1);
            time(joinedAt);
            enrollment.enroll(owner, UUID.randomUUID(), joinedAt, new RanchStateAssembler.ExternalProjection(
                    false, "DISABLED", false, false, null, null, List.of()));
            assertThat(rewardWriter.decide(original.toEnvelope())).isEqualTo(actual);
            var retry = active.withActiveUser(owner, () -> outboxAdmin.retry(owner, original.eventId(),
                    UUID.randomUUID(), new SourceOutboxAdminRetryRequest("ACK_JOIN_TEST"), dbNow()));
            assertThat(retry.disposition()).isEqualTo(SourceOutboxAdminRetryAck.Disposition.ALREADY_TERMINAL);
            assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(original.eventId(), token, dbNow(), actual))).isFalse();
            assertTerminal(original, "NOT_ENROLLED"); assertNoRanchReward();

            // 新sessionだけでは同entry同週のcanonical重複になるので、別entryを実完了する。
            var fresh = completedSource(joinedAt.plusSeconds(2), 2);
            assertThat(fresh.canonicalSourceId()).isNotEqualTo(original.canonicalSourceId());
            UUID freshToken = leaseOnlyOwnPending(fresh);
            var awarded = rewardWriter.decide(fresh.toEnvelope());
            assertThat(awarded.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.AWARDED);
            assertThat(awarded.awardedPoints()).isEqualTo(1); assertThat(awarded.decisionId()).isNotNull();
            assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(fresh.eventId(), freshToken, dbNow(), awarded))).isTrue();
            assertTerminal(fresh, "AWARDED"); assertTerminal(original, "NOT_ENROLLED");
            assertThat(sourceCount(false)).isEqualTo(2); assertThat(sourceCount(true)).isEqualTo(2);
            assertThat(decisions.countByUserId(owner)).isEqualTo(1);
            var saved = decisions.findById(awarded.decisionId()).orElseThrow();
            assertThat(saved.getOccurredAt()).isEqualTo(fresh.occurredAt());
            assertThat(saved.getRewardWeek()).isEqualTo(fresh.facts().completionWeek());
            assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(owner)).hasSize(1)
                    .first().satisfies(row -> { assertThat(row.getDecisionId()).isEqualTo(awarded.decisionId());
                        assertThat(row.getOccurredAt()).isEqualTo(fresh.occurredAt()); assertThat(row.getDeltaPoints()).isEqualTo(1); });
            assertThat(ranchOwners.findByUserId(owner).orElseThrow().getBalance()).isEqualTo(1);
            assertThat(budgets.findByUserIdAndWeekStartsOn(owner, fresh.facts().completionWeek()).orElseThrow().getAwardedTotal()).isEqualTo(1);
        } finally {
            ranchPurge.purgeUser(owner);
            policies.findById(policyId).ifPresent(policies::delete);
            if (previousControl == null) controls.deleteById(1); else controls.saveAndFlush(previousControl);
        }
    }

    private ReflectionRecallRewardPayload completedSource(Instant completedAt, int expectedSources) throws Exception {
        var theme = themes.saveAndFlush(ReflectionThemeEntity.builder().userId(owner).title("源ACK fixture").build());
        var entry = entries.saveAndFlush(ReflectionEntryEntity.builder().userId(owner).themeId(theme.getId())
                .targetDate(LocalDate.parse("2030-10-06")).structuredContent("{\"free_note\":\"本人原文\"}")
                .visibility(ReflectionVisibility.PRIVATE).build());
        var started = operations.start(owner, entry.getId(), UUID.randomUUID(), mapper.createObjectNode());
        var body = mapper.valueToTree(Map.of("version", started.version(), "answers", List.of(Map.of(
                "promptId", started.prompts().getFirst().id().toString(), "state", "ANSWERED", "text", "本人回答")), "selfRating", "REMEMBERED"));
        time(completedAt);
        var completed = operations.complete(owner, started.id(), UUID.randomUUID(), body);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(sourceCount(false)).isEqualTo(expectedSources); assertThat(sourceCount(true)).isEqualTo(expectedSources);
        });
        String encoded = jdbc.queryForObject("SELECT payload_json FROM reflection_ranch_outboxes WHERE recipient_user_id=? "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.canonicalSourceId'))=?", String.class, owner, entry.getId().toString());
        var payload = mapper.readValue(encoded, ReflectionRecallRewardPayload.class);
        assertThat(payload.occurredAt()).isEqualTo(completed.completedAt());
        assertThat(payload.facts().sessionId()).isEqualTo(completed.id());
        assertThat(payload.facts().completionWeek()).isEqualTo(completed.rewardWeek());
        return payload;
    }

    private UUID leaseOnlyOwnPending(ReflectionRecallRewardPayload payload) {
        UUID token = UUID.randomUUID();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        Instant expires = tx.execute(status -> {
            var current = jdbc.queryForMap("SELECT status,attempt_count,lease_token FROM reflection_ranch_outboxes "
                    + "WHERE id=? AND recipient_user_id=? FOR UPDATE", bytes(payload.eventId()), owner);
            assertThat(current.get("status")).isEqualTo("PENDING");
            assertThat(((Number) current.get("attempt_count")).intValue()).isZero(); assertThat(current.get("lease_token")).isNull();
            var now = dbNow(); return outboxRows.lease(payload.eventId(), token, now.plusSeconds(30), now).orElseThrow();
        });
        var saved = jdbc.queryForObject("SELECT lease_expires_at FROM reflection_ranch_outboxes WHERE id=? "
                + "AND recipient_user_id=? AND status='LEASED' AND lease_token=?",
                (rs, index) -> rs.getTimestamp(1, JdbcUtcCalendar.fresh()).toInstant(), bytes(payload.eventId()), owner, bytes(token));
        assertThat(expires).isEqualTo(saved); return token;
    }

    private void assertTerminal(ReflectionRecallRewardPayload payload, String outcome) throws Exception {
        var row = jdbc.queryForMap("SELECT status,terminal_outcome,acked_at,attempt_count,lease_token,lease_expires_at,payload_json "
                + "FROM reflection_ranch_outboxes WHERE id=? AND recipient_user_id=?", bytes(payload.eventId()), owner);
        assertThat(row.get("status")).isEqualTo("ACKED"); assertThat(row.get("terminal_outcome")).isEqualTo(outcome);
        assertThat(row.get("acked_at")).isNotNull(); assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(row.get("lease_token")).isNull(); assertThat(row.get("lease_expires_at")).isNull();
        var saved = mapper.readValue((String) row.get("payload_json"), ReflectionRecallRewardPayload.class);
        assertThat(saved.equals(payload)).as("保存済み技術factはACKで変更しない").isTrue();
    }
    private void assertNoRanchReward() {
        assertThat(decisions.countByUserId(owner)).isZero(); assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(owner)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranch_week_budgets WHERE user_id=?", Integer.class, owner)).isZero();
    }
    private Instant dbNow() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", (rs, index) -> rs.getTimestamp(1, JdbcUtcCalendar.fresh()).toInstant()); }
    private byte[] bytes(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private void publishAckPolicy(UUID id) {
        var rules = new EnumMap<RanchRewardSourceType, RanchRewardPolicySnapshot.SourceRule>(RanchRewardSourceType.class);
        for (var type : RanchRewardSourceType.values()) rules.put(type, new RanchRewardPolicySnapshot.SourceRule(true, 1, 20));
        var snapshot = new RanchRewardPolicySnapshot(id, 1, START.minusSeconds(3600), true, 20, rules,
                new RanchRewardPolicySnapshot.DeliverySettings(25, 30, 4, 2, 20), "PHASE1_TEST");
        var encoded = RanchRewardPolicyCodec.encode(snapshot, mapper);
        var policy = RanchRewardPolicyEntity.builder().versionNumber(1).effectiveAt(snapshot.effectiveAt()).schemaVersion(1)
                .settingsJson(encoded.json()).contentHash(encoded.sha256()).publishedBy(owner).publishedAt(START).createdAt(START).build();
        policy.setId(id); policies.saveAndFlush(policy);
    }

    /** getDate/LocalDate JDBC変換を通さず、実SQL DATEの文字値を直接比較する。 */
    private String physicalWeek(UUID sessionId) {
        return jdbc.queryForObject("SELECT CAST(reward_week AS CHAR) FROM reflection_recall_sessions WHERE id=? AND user_id=?",
                String.class, ByteBuffer.allocate(16).putLong(sessionId.getMostSignificantBits())
                        .putLong(sessionId.getLeastSignificantBits()).array(), owner);
    }

    private int sourceCount(boolean outbox) {
        return jdbc.queryForObject(outbox
                        ? "SELECT COUNT(*) FROM reflection_ranch_outboxes WHERE recipient_user_id=?"
                        : "SELECT COUNT(*) FROM reflection_ranch_witnesses WHERE recipient_user_id=?",
                Integer.class, owner);
    }

    private void time(Instant value) { ((RecallTemporalClock) clock).current.set(value); }

    /** このtest contextだけの時計。共有JVM既定TZ・他contextのClockに触れない。 */
    private static final class RecallTemporalClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;
        private RecallTemporalClock() { this(new AtomicReference<>(START), ZoneOffset.UTC); }
        private RecallTemporalClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = java.util.Objects.requireNonNull(zone);
        }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) {
            return this.zone.equals(zone) ? this : new RecallTemporalClock(current, zone);
        }
        @Override public Instant instant() { return current.get(); }
    }
}
