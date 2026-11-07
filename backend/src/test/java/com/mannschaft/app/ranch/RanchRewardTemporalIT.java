package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.entity.RanchRewardPausePeriodEntity;
import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPausePeriodRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.repository.RanchWeekBudgetRepository;
import com.mannschaft.app.ranch.reward.RanchRewardPolicyCodec;
import com.mannschaft.app.ranch.reward.RanchRewardPolicySnapshot;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.RanchRewardWriter;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchOwnerCommandWriter;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** AC34/35のconsumer時系列を実MySQLで検査する。native source/ACK・HTTP認可は別契約。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchRewardTemporalIT extends AbstractMySqlIntegrationTest {
    private static final Instant JOIN = Instant.parse("2030-09-30T01:00:00Z");
    private static final Instant FACT = Instant.parse("2030-10-07T00:00:02Z");
    private static final Instant NEXT = Instant.parse("2030-10-14T12:00:00Z");
    private static final LocalDate WEEK = LocalDate.parse("2030-10-07");
    private static final LocalDate NEXT_WEEK = LocalDate.parse("2030-10-14");
    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchOwnerCommandWriter participation;
    @Autowired private RanchRewardWriter writer;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchRewardDecisionRepository decisions;
    @Autowired private RanchPointLedgerRepository ledger;
    @Autowired private RanchWeekBudgetRepository budgets;
    @Autowired private RanchOperationalControlRepository controls;
    @Autowired private RanchRewardPolicyRepository policies;
    @Autowired private RanchRewardPausePeriodRepository pauses;
    @Autowired private RanchPurgeService purge;
    @Autowired private ObjectMapper json;
    @TestBean(name = "utcClock", methodName = "rewardTemporalClock") private Clock clock;
    private Long userId;
    private UUID policyId;
    private UUID pauseId;
    private RanchOperationalControlEntity previousControl;

    static Clock rewardTemporalClock() { return new RewardTemporalClock(); }

    @BeforeEach void prepareOnlyOwnedFixture() {
        time(JOIN);
        previousControl = controls.findById(1).orElse(null);
        RanchTestFixture.operationalControl(controls);
        var control = controls.findById(1).orElseThrow();
        ReflectionTestUtils.setField(control, "deliveryPaused", false);
        controls.saveAndFlush(control);
        userId = users.saveAndFlush(RanchTestFixture.user()).getId();
        policyId = UUID.randomUUID();
        var rules = new EnumMap<RanchRewardSourceType, RanchRewardPolicySnapshot.SourceRule>(RanchRewardSourceType.class);
        for (var type : RanchRewardSourceType.values()) rules.put(type, new RanchRewardPolicySnapshot.SourceRule(true, 1, 20));
        var snapshot = new RanchRewardPolicySnapshot(policyId, 1, JOIN.minusSeconds(3600), true, 20,
                rules, new RanchRewardPolicySnapshot.DeliverySettings(25, 30, 4, 2, 20), "PHASE1_TEST");
        var encoded = RanchRewardPolicyCodec.encode(snapshot, json);
        var policy = RanchRewardPolicyEntity.builder().versionNumber(1).effectiveAt(snapshot.effectiveAt())
                .schemaVersion(1).settingsJson(encoded.json()).contentHash(encoded.sha256())
                .publishedBy(userId).publishedAt(snapshot.effectiveAt()).createdAt(snapshot.effectiveAt()).build();
        policy.setId(policyId);
        policies.saveAndFlush(policy);
    }

    @AfterEach void removeOwnedRowsAndRestoreControlAndClock() {
        try {
            if (userId != null) purge.purgeUser(userId);
            if (pauseId != null) pauses.deleteById(pauseId);
            if (policyId != null) policies.deleteById(policyId);
            if (previousControl == null) controls.deleteById(1); else controls.saveAndFlush(previousControl);
            if (userId != null) users.deleteById(userId);
        } finally { time(JOIN); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RanchRewardSourceType.class, names = {"ATTENDANCE_RESPONSE", "TIMELINE_ORIGINAL", "BLOG_FIRST_PUBLISH"})
    void 三sourceは翌週再配送でも元決定と元週枠を保ち別factだけ新週へ加算する(RanchRewardSourceType type) throws Exception {
        enroll(JOIN); time(NEXT);
        String id = UUID.randomUUID().toString();
        var first = writer.decide(event(type, id, FACT));
        assertDecision(first, RanchRewardDeliveryOutcome.Outcome.AWARDED, FACT, WEEK, 1);
        var originalLedger = ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId).getFirst();
        var originalBudget = budgets.findByUserIdAndWeekStartsOn(userId, WEEK).orElseThrow();
        assertThat(originalLedger.getDecisionId()).isEqualTo(first.decisionId());
        assertThat(originalLedger.getOccurredAt()).isEqualTo(FACT);
        assertThat(originalLedger.getDeltaPoints()).isEqualTo(1);
        time(NEXT.plusSeconds(60));
        // event IDは新しく、native canonical sourceと元発生時刻は同じ。
        assertThat(writer.decide(event(type, id, FACT))).isEqualTo(first);
        assertThat(decisions.countByUserId(userId)).isEqualTo(1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).extracting(row -> row.getId()).containsExactly(originalLedger.getId());
        var unchanged = budgets.findByUserIdAndWeekStartsOn(userId, WEEK).orElseThrow();
        assertThat(unchanged.getAwardedTotal()).isEqualTo(originalBudget.getAwardedTotal()).isEqualTo(1);
        assertThat(unchanged.getSourceCounts()).isEqualTo(originalBudget.getSourceCounts());
        assertThat(unchanged.getVersion()).isEqualTo(originalBudget.getVersion());
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, NEXT_WEEK)).isEmpty();
        assertDecision(writer.decide(event(type, UUID.randomUUID().toString(), NEXT.minusSeconds(1))),
                RanchRewardDeliveryOutcome.Outcome.AWARDED, NEXT.minusSeconds(1), NEXT_WEEK, 1);
        assertThat(json.readTree(budgets.findByUserIdAndWeekStartsOn(userId, NEXT_WEEK).orElseThrow().getSourceCounts()).path(type.name()).longValue()).isEqualTo(1);
        assertThat(owners.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(2);
        assertThat(decisions.countByUserId(userId)).isEqualTo(2);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(2);
    }

    @Test void 発生時参加状態と報酬停止の半開境界は配送時の現在状態より優先する() {
        enroll(JOIN);
        Instant paused = FACT.plusSeconds(10), resumed = FACT.plusSeconds(20);
        participation.pause(userId, UUID.randomUUID(), version(), paused);
        time(paused.plusSeconds(1));
        assertThat(owners.findByUserId(userId).orElseThrow().getStatus()).isEqualTo(ParticipationStatus.PAUSED);
        assertDecision(writer.decide(fact(FACT)), RanchRewardDeliveryOutcome.Outcome.AWARDED, FACT, WEEK, 1);
        participation.resume(userId, UUID.randomUUID(), version(), resumed);
        time(resumed.plusSeconds(30));
        assertThat(owners.findByUserId(userId).orElseThrow().getStatus()).isEqualTo(ParticipationStatus.ACTIVE);
        assertDecision(writer.decide(fact(paused)), RanchRewardDeliveryOutcome.Outcome.NOT_PARTICIPATING, paused, WEEK, 0);
        assertDecision(writer.decide(fact(resumed)), RanchRewardDeliveryOutcome.Outcome.AWARDED, resumed, WEEK, 1);
        Instant stop = resumed.plusSeconds(1), restart = resumed.plusSeconds(2);
        var period = pauses.saveAndFlush(RanchRewardPausePeriodEntity.builder().startsAt(stop).endsAt(restart)
                .reasonCode("TEMPORAL_TEST").changedBy(userId).createdAt(stop).build());
        pauseId = period.getId();
        assertDecision(writer.decide(fact(stop)), RanchRewardDeliveryOutcome.Outcome.REWARDS_PAUSED, stop, WEEK, 0);
        assertDecision(writer.decide(fact(restart)), RanchRewardDeliveryOutcome.Outcome.AWARDED, restart, WEEK, 1);
        assertThat(decisions.countByUserId(userId)).isEqualTo(5);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(3);
        assertThat(owners.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(3);
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, WEEK).orElseThrow().getAwardedTotal()).isEqualTo(3);
    }

    @Test void 未参加発生factは開始後再試行でも未参加で開始後の別factは獲得できる() {
        var original = fact(FACT);
        var before = writer.decide(original);
        assertThat(before.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.NOT_ENROLLED);
        assertThat(before.decisionId()).isNull(); assertThat(before.awardedPoints()).isZero();
        enroll(FACT.plusSeconds(1)); time(FACT.plusSeconds(3));
        assertThat(writer.decide(original)).isEqualTo(before);
        assertThat(decisions.countByUserId(userId)).isZero();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).isEmpty();
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, WEEK)).isEmpty();
        assertDecision(writer.decide(fact(FACT.plusSeconds(2))), RanchRewardDeliveryOutcome.Outcome.AWARDED, FACT.plusSeconds(2), WEEK, 1);
        assertThat(owners.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(decisions.countByUserId(userId)).isEqualTo(1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(1);
    }

    private void enroll(Instant at) {
        enrollment.enroll(userId, UUID.randomUUID(), at, new RanchStateAssembler.ExternalProjection(
                false, "DISABLED", false, false, null, null, List.of()));
    }
    private RanchVersionRequest version() { return new RanchVersionRequest(Long.toString(owners.findByUserId(userId).orElseThrow().getVersion())); }
    private RanchRewardEnvelope fact(Instant at) { return event(RanchRewardSourceType.TIMELINE_ORIGINAL, UUID.randomUUID().toString(), at); }
    private void assertDecision(RanchRewardDeliveryOutcome result, RanchRewardDeliveryOutcome.Outcome status, Instant at, LocalDate week, long points) {
        assertThat(result.outcome()).isEqualTo(status); assertThat(result.awardedPoints()).isEqualTo(points);
        assertThat(result.decisionId()).isNotNull();
        var saved = decisions.findById(result.decisionId()).orElseThrow();
        assertThat(saved.getOccurredAt()).isEqualTo(at); assertThat(saved.getRewardWeek()).isEqualTo(week);
        assertThat(saved.getDecidedAt()).isEqualTo(clock.instant()); assertThat(saved.getAwardedPoints()).isEqualTo(points);
    }
    private RanchRewardEnvelope event(RanchRewardSourceType type, String id, Instant at) {
        RanchRewardEnvelope.SourceFacts facts = switch (type) {
            case ATTENDANCE_RESPONSE -> new RanchRewardEnvelope.Attendance(RanchRewardEnvelope.AttendanceStatus.ATTENDING, false, true);
            case TIMELINE_ORIGINAL -> new RanchRewardEnvelope.Timeline(RanchRewardEnvelope.PostOrigin.ORIGINAL, true);
            case BLOG_FIRST_PUBLISH -> new RanchRewardEnvelope.Blog(RanchRewardEnvelope.PublicationKind.MANUAL, true);
            default -> throw new IllegalArgumentException("非AR三sourceだけを試験する");
        };
        var origin = switch (type) {
            case ATTENDANCE_RESPONSE -> RanchRewardEnvelope.Origin.SELF_RESPONSE;
            case TIMELINE_ORIGINAL -> RanchRewardEnvelope.Origin.ORIGINAL;
            case BLOG_FIRST_PUBLISH -> RanchRewardEnvelope.Origin.FIRST_PUBLISH;
            default -> throw new IllegalArgumentException("非AR三sourceだけを試験する");
        };
        return new RanchRewardEnvelope(UUID.randomUUID(), 1, type, RanchRewardEnvelope.IdType.UUID, id,
                RanchRewardEnvelope.ScopeType.PERSONAL, null, null, RanchRewardEnvelope.ActorKind.USER,
                userId, null, userId, userId, at, origin, facts);
    }
    private void time(Instant at) { ((RewardTemporalClock) clock).current.set(at); }
    private static final class RewardTemporalClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;
        private RewardTemporalClock() { this(new AtomicReference<>(JOIN), ZoneOffset.UTC); }
        private RewardTemporalClock(AtomicReference<Instant> current, ZoneId zone) { this.current = current; this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId other) { return zone.equals(other) ? this : new RewardTemporalClock(current, java.util.Objects.requireNonNull(other)); }
        @Override public Instant instant() { return current.get(); }
    }
}
