package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.repository.RanchWeekBudgetRepository;
import com.mannschaft.app.ranch.reward.RanchRewardPolicyCodec;
import com.mannschaft.app.ranch.reward.RanchRewardPolicySnapshot;
import com.mannschaft.app.ranch.reward.RanchRewardProjectionReader;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.RanchRewardWriter;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** source ACK と分離した Ranch TX の零決定・週凍結・上限・replay を実 MySQL で検証する。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchRewardWriterIT extends AbstractMySqlIntegrationTest {
    private static final Instant ENROLLED = Instant.parse("2026-10-05T01:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-10-05T02:00:00Z");
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());

    @Autowired UserRepository users;
    @Autowired RanchEnrollmentWriter enrollment;
    @Autowired RanchRewardWriter writer;
    @Autowired RanchRewardProjectionReader projection;
    @Autowired RanchOperationalControlRepository controls;
    @Autowired RanchRewardPolicyRepository policies;
    @Autowired RanchRewardDecisionRepository decisions;
    @Autowired RanchWeekBudgetRepository budgets;
    @Autowired RanchPointLedgerRepository ledger;
    @Autowired RanchOwnerRepository owners;
    @Autowired RanchPurgeService purge;
    @Autowired ObjectMapper json;
    private Long enrolledUserId;
    private UUID publishedPolicyId;

    @BeforeEach
    void seedOperationalControl() {
        RanchTestFixture.operationalControl(controls);
    }

    @AfterEach
    void removeOnlyThisCasesRows() {
        if (enrolledUserId != null) purge.purgeUser(enrolledUserId);
        if (publishedPolicyId != null) policies.deleteById(publishedPolicyId);
    }

    @Test
    void absentPolicySavesZeroDecisionAndLaterPublicationDoesNotRewriteReplay() {
        Long userId = enroll();
        assertThat(projection.current(userId, OCCURRED).deliveryPaused()).isTrue();
        enableDelivery();
        var fact = recall(userId, UUID.randomUUID(), UUID.randomUUID());
        var first = writer.decide(fact);
        assertThat(first.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.SOURCE_DISABLED);
        assertThat(first.decisionId()).isNotNull();
        assertThat(first.awardedPoints()).isZero();
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, LocalDate.parse("2026-10-05"))).isEmpty();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).isEmpty();
        assertThat(projection.current(userId, OCCURRED).rewardsStatus()).isEqualTo("DISABLED");

        publish(userId, 5, 4, 3);
        assertThat(writer.decide(fact)).isEqualTo(first);
        assertThat(decisions.countByUserId(userId)).isEqualTo(1);
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, LocalDate.parse("2026-10-05"))).isEmpty();
        assertThat(projection.current(userId, OCCURRED).weekBudget().globalCap()).isEqualTo("5");
    }

    @Test
    void globalCapConsumesQualifiedCountAndSameCanonicalReplayAddsNothing() throws Exception {
        Long userId = enroll();
        enableDelivery();
        publish(userId, 5, 4, 3);
        var first = recall(userId, UUID.randomUUID(), UUID.randomUUID());
        var second = recall(userId, UUID.randomUUID(), UUID.randomUUID());
        var third = recall(userId, UUID.randomUUID(), UUID.randomUUID());
        assertThat(writer.decide(first).awardedPoints()).isEqualTo(4);
        assertThat(writer.decide(second).awardedPoints()).isEqualTo(1);
        var capped = writer.decide(third);
        assertThat(capped.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.CAPPED);
        assertThat(capped.awardedPoints()).isZero();
        assertThat(writer.decide(first).awardedPoints()).isEqualTo(4);
        assertThat(decisions.countByUserId(userId)).isEqualTo(3);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(2);
        assertThat(owners.findByUserId(userId).orElseThrow().getBalance()).isEqualTo(5);
        var budget = budgets.findByUserIdAndWeekStartsOn(userId,
                LocalDate.parse("2026-10-05")).orElseThrow();
        assertThat(budget.getAwardedTotal()).isEqualTo(5);
        assertThat(json.readTree(budget.getSourceCounts())
                .path(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE.name()).longValue()).isEqualTo(3);
        var view = projection.current(userId, OCCURRED);
        assertThat(view.rewardsStatus()).isEqualTo("ENABLED");
        assertThat(view.weekBudget().remaining()).isEqualTo("0");
        assertThat(view.weekBudget().personalRequiredCount()).isEqualTo("0");
        assertThat(view.weekBudget().personalCompletedCount()).isEqualTo(3);
        assertThat(decisions.countByUserId(userId)).isEqualTo(3);
    }

    @Test
    void explicitlyDisabledPolicySavesZeroDecisionWithoutCreatingWeekBudget() {
        Long userId = enroll();
        enableDelivery();
        publish(userId, 5, 4, 3, false);
        var fact = recall(userId, UUID.randomUUID(), UUID.randomUUID());
        var result = writer.decide(fact);
        assertThat(result.outcome()).isEqualTo(RanchRewardDeliveryOutcome.Outcome.SOURCE_DISABLED);
        assertThat(result.awardedPoints()).isZero();
        assertThat(decisions.findById(result.decisionId()).orElseThrow().getPolicyId()).isNotNull();
        assertThat(budgets.findByUserIdAndWeekStartsOn(userId, LocalDate.parse("2026-10-05"))).isEmpty();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(userId)).isEmpty();
    }

    private Long enroll() {
        Long userId = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrolledUserId = userId;
        enrollment.enroll(userId, UUID.randomUUID(), ENROLLED, PROJECTION);
        return userId;
    }

    private void enableDelivery() {
        var control = controls.findById(1).orElseThrow();
        ReflectionTestUtils.setField(control, "deliveryPaused", false);
        controls.saveAndFlush(control);
    }

    private void publish(Long actor, long cap, long amount, long countLimit) {
        publish(actor, cap, amount, countLimit, true);
    }

    private void publish(Long actor, long cap, long amount, long countLimit, boolean enabled) {
        UUID policyId = UUID.randomUUID();
        EnumMap<RanchRewardSourceType, RanchRewardPolicySnapshot.SourceRule> sources =
                new EnumMap<>(RanchRewardSourceType.class);
        for (var type : RanchRewardSourceType.values()) {
            sources.put(type, new RanchRewardPolicySnapshot.SourceRule(true, amount, countLimit));
        }
        var snapshot = new RanchRewardPolicySnapshot(policyId, 1,
                ENROLLED.minusSeconds(3600), enabled, cap, sources,
                new RanchRewardPolicySnapshot.DeliverySettings(25, 30, 4, 2, 20),
                "PHASE1_TEST");
        var encoded = RanchRewardPolicyCodec.encode(snapshot, json);
        var entity = RanchRewardPolicyEntity.builder().versionNumber(1)
                .effectiveAt(snapshot.effectiveAt()).schemaVersion(1)
                .settingsJson(encoded.json()).contentHash(encoded.sha256())
                .publishedBy(actor).publishedAt(snapshot.effectiveAt()).createdAt(snapshot.effectiveAt()).build();
        entity.setId(policyId);
        policies.saveAndFlush(entity);
        publishedPolicyId = policyId;
    }

    private RanchRewardEnvelope recall(Long userId, UUID entryId, UUID sessionId) {
        return new RanchRewardEnvelope(UUID.randomUUID(), 1,
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                RanchRewardEnvelope.IdType.UUID, entryId.toString(),
                RanchRewardEnvelope.ScopeType.PERSONAL, null, null,
                RanchRewardEnvelope.ActorKind.USER, userId, null, userId, userId,
                OCCURRED, RanchRewardEnvelope.Origin.PERSONAL_COMPLETION,
                new RanchRewardEnvelope.PersonalRecall(sessionId, 4,
                        LocalDate.parse("2026-10-05"), true));
    }
}
