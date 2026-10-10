package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.dto.FeedingResult;
import com.mannschaft.app.ranch.dto.RanchInteractionRequest;
import com.mannschaft.app.ranch.entity.RanchCareWeekBudgetEntity;
import com.mannschaft.app.ranch.repository.RanchAffinityUnitRepository;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.repository.RanchWeekBudgetRepository;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchFeedingWriter;
import com.mannschaft.app.ranch.service.RanchOwnerCommandWriter;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchRuleProvider;
import com.mannschaft.app.ranch.service.RanchTouchWriter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 開発規則の週枠100をUTC同日内に使い切り、台帳も原子的に残す実MySQL検証。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchFeedingWriterIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    private static final Instant EGG_START = Instant.parse("2026-10-04T02:00:00.123456789Z");
    private static final Instant FEED_AT = EGG_START.plusSeconds(604801);

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchFeedingWriter feeding;
    @Autowired private RanchOwnerCommandWriter ownerCommands;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchCareWeekBudgetRepository budgets;
    @Autowired private RanchAffinityUnitRepository affinities;
    @Autowired private RanchCommandRepository commands;
    @Autowired private RanchPointLedgerRepository ledger;
    @Autowired private RanchRewardDecisionRepository decisions;
    @Autowired private RanchWeekBudgetRepository rewardBudgets;
    @Autowired private RanchRuleProvider rules;
    @Autowired private RanchTouchWriter touching;
    @Autowired private RanchPurgeService purge;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private Long me;

    @BeforeEach
    void createHathedSyntheticDinosaur() {
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrollment.enroll(me, UUID.randomUUID(), EGG_START, PROJECTION);
        var dinosaur = dinosaurs.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(dinosaur, "selectionConfirmedAt", EGG_START.plusSeconds(1));
        dinosaur.hatch("テスト", EGG_START.plusSeconds(604800));
        dinosaurs.saveAndFlush(dinosaur);
    }

    @AfterEach
    void removeOnlyThisSyntheticUserAndRanchRows() {
        if (me == null) return;
        purge.purgeUser(me);
        for (String table : List.of("ranch_owners", "ranch_dinosaurs", "ranch_commands",
                "ranch_point_ledger", "ranch_care_week_budgets", "ranch_affinity_units",
                "ranch_room_placements", "ranch_collectible_inventory",
                "ranch_participation_periods", "ranch_reward_decisions", "ranch_week_budgets")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
                    Long.class, me)).as("本人fixtureの残存行: %s", table).isZero();
        }
        users.deleteById(me);
    }

    @Test
    void concurrentFreeCareWithOneXpRemainingCommitsOnceAndExplicitRetryStillAllowsTouch()
            throws Exception {
        var rule = rules.currentCareRule(FEED_AT).orElseThrow();
        var owner = owners.findByUserId(me).orElseThrow();
        var dinosaur = dinosaurs.findByUserId(me).orElseThrow();
        // 週残1XPの境界fixture。活動報酬や実利用者の行を流用しない。
        dinosaur.applyCareXp(99, rule.juvenileXp(), rule.adultXp());
        dinosaurs.saveAndFlush(dinosaur);
        budgets.saveAndFlush(RanchCareWeekBudgetEntity.builder()
                .ownerId(owner.getId()).userId(me).weekStartsOn(LocalDate.of(2026, 10, 5))
                .ruleId(rule.ruleId()).ruleSnapshot(json.writeValueAsString(Map.of(
                        "ruleVersion", rule.ruleVersion(), "amountXp", rule.amountXp(),
                        "weeklyCapXp", rule.weeklyCapXp())))
                .weeklyCapXp(rule.weeklyCapXp()).awardedXp(99).version(0)
                .createdAt(FEED_AT.minusSeconds(1)).build());
        long beforeCommands = commands.countByUserId(me);
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();

        var attempts = concurrentFeed(firstKey, secondKey);
        var successful = attempts.stream().filter(attempt -> attempt.result() != null).toList();
        var rejected = attempts.stream().filter(attempt -> attempt.failure() != null).toList();
        assertThat(successful).hasSize(1);
        assertThat(rejected).hasSize(1);
        var winner = successful.get(0);
        var loser = rejected.get(0);
        assertThat(loser.failure().getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007);
        assertThat(loser.failure().getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(winner.result().gainedXp()).isEqualTo("1");
        assertThat(winner.result().costPoints()).isEqualTo("0");
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getXp()).isEqualTo(100);
        assertThat(owners.findByUserId(me).orElseThrow().getBalance()).isZero();
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(commands.countByUserId(me)).isEqualTo(beforeCommands + 1);
        assertThat(commands.findByUserIdAndIdempotencyKey(me, loser.key())).isEmpty();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getCommandId()).isEqualTo(winner.result().commandId());
                    assertThat(row.getDeltaXp()).isEqualTo(1);
                    assertThat(row.getDeltaPoints()).isZero();
                });
        var committedBudget = budgets.findByUserIdAndWeekStartsOn(me,
                LocalDate.of(2026, 10, 5)).orElseThrow();
        assertThat(committedBudget.getAwardedXp()).isEqualTo(100);
        assertThat(committedBudget.getVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranch_care_week_budgets WHERE user_id = ?",
                Long.class, me)).isEqualTo(1);

        assertThatThrownBy(() -> feeding.feed(me, loser.key(), new RanchVersionRequest("0"), FEED_AT))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_007);
                    assertThat(failure.getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
                });
        assertThat(commands.countByUserId(me)).isEqualTo(beforeCommands + 1);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(1);
        assertThat(budgets.findByUserIdAndWeekStartsOn(me, LocalDate.of(2026, 10, 5))
                .orElseThrow().getVersion()).isEqualTo(1);
        // 409を自動retryで隠さず、本人の最新versionによる明示的な再操作を別に送る。
        var capped = feeding.feed(me, loser.key(), new RanchVersionRequest("1"), FEED_AT.plusSeconds(1));
        assertThat(capped.gainedXp()).isEqualTo("0");
        assertThat(capped.costPoints()).isEqualTo("0");
        assertThat(capped.isGrowthCapped()).isTrue();
        assertThat(feeding.feed(me, winner.key(), new RanchVersionRequest("0"),
                FEED_AT.plusSeconds(2))).isEqualTo(winner.result());
        var touched = touching.touch(me, UUID.randomUUID(),
                new RanchInteractionRequest(InteractionKind.TOUCH, "2"), FEED_AT.plusSeconds(3));
        assertThat(touched.dinosaurId()).isEqualTo(dinosaur.getId());
        assertThat(touched.reactionKey()).isEqualTo("DINOSAUR_TOUCH");
        assertThat(touched.affinityChanged()).isTrue();
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getXp()).isEqualTo(100);
        assertThat(owners.findByUserId(me).orElseThrow().getBalance()).isZero();
        assertThat(commands.countByUserId(me)).isEqualTo(beforeCommands + 3);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(2)
                .allSatisfy(row -> assertThat(row.getDeltaPoints()).isZero());
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me).stream()
                .mapToLong(row -> row.getDeltaXp()).sum()).isEqualTo(1);
        var cappedBudget = budgets.findByUserIdAndWeekStartsOn(me,
                LocalDate.of(2026, 10, 5)).orElseThrow();
        assertThat(cappedBudget.getAwardedXp()).isEqualTo(100);
        assertThat(cappedBudget.getVersion()).isEqualTo(2);
    }

    @Test
    void freeUnaffiliatedDinosaurReachesAdultAcrossActualUtcWeekBudgets() {
        Instant sunday = Instant.parse("2026-10-11T23:59:57Z");
        Instant monday = Instant.parse("2026-10-12T00:00:00Z");
        var before = dinosaurs.findByUserId(me).orElseThrow();
        UUID dinosaurId = before.getId();
        String permanentName = before.getName();
        String frozenGrowth = before.getGrowthRuleSnapshot();
        String frozenEgg = before.getEggRuleSnapshot();
        assertNoMembershipActivityOrPoints();

        for (int index = 0; index < 3; index++) {
            var result = feeding.feed(me, UUID.randomUUID(),
                    new RanchVersionRequest(Integer.toString(index)), sunday.plusSeconds(index));
            assertThat(result.gainedXp()).isEqualTo("20");
            assertThat(result.costPoints()).isEqualTo("0");
            assertThat(result.dinosaurId()).isEqualTo(dinosaurId);
        }
        var sundayBudget = budgets.findByUserIdAndWeekStartsOn(me,
                LocalDate.of(2026, 10, 5)).orElseThrow();
        assertThat(sundayBudget.getAwardedXp()).isEqualTo(60);
        assertThat(sundayBudget.getVersion()).isEqualTo(3);
        String frozenCare = sundayBudget.getRuleSnapshot();
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getStage()).isEqualTo(DinosaurStage.JUVENILE);
        assertThat(budgets.findByUserIdAndWeekStartsOn(me, LocalDate.of(2026, 10, 12))).isEmpty();

        for (int index = 0; index < 2; index++) {
            var result = feeding.feed(me, UUID.randomUUID(),
                    new RanchVersionRequest(Integer.toString(index + 3)), monday.plusSeconds(index));
            assertThat(result.gainedXp()).isEqualTo("20");
            assertThat(result.costPoints()).isEqualTo("0");
            assertThat(result.dinosaurId()).isEqualTo(dinosaurId);
        }
        var mondayBudget = budgets.findByUserIdAndWeekStartsOn(me,
                LocalDate.of(2026, 10, 12)).orElseThrow();
        assertThat(mondayBudget.getId()).isNotEqualTo(sundayBudget.getId());
        assertThat(mondayBudget.getAwardedXp()).isEqualTo(40);
        assertThat(mondayBudget.getVersion()).isEqualTo(2);
        assertThat(mondayBudget.getRuleSnapshot()).isEqualTo(frozenCare);
        var oldBudget = budgets.findByUserIdAndWeekStartsOn(me,
                LocalDate.of(2026, 10, 5)).orElseThrow();
        assertThat(oldBudget.getAwardedXp()).isEqualTo(60);
        assertThat(oldBudget.getRuleSnapshot()).isEqualTo(frozenCare);
        var adult = dinosaurs.findByUserId(me).orElseThrow();
        assertThat(adult.getId()).isEqualTo(dinosaurId);
        assertThat(adult.getName()).isEqualTo(permanentName);
        assertThat(adult.getGrowthRuleSnapshot()).isEqualTo(frozenGrowth);
        assertThat(adult.getEggRuleSnapshot()).isEqualTo(frozenEgg);
        assertThat(adult.getXp()).isEqualTo(100);
        assertThat(adult.getStage()).isEqualTo(DinosaurStage.ADULT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ranch_care_week_budgets WHERE user_id = ?",
                Long.class, me)).isEqualTo(2);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(5)
                .allSatisfy(row -> {
                    assertThat(row.getEntryKind()).isEqualTo("CARE");
                    assertThat(row.getDeltaPoints()).isZero();
                    assertThat(row.getDeltaXp()).isEqualTo(20);
                });
        assertNoMembershipActivityOrPoints();
    }

    private void assertNoMembershipActivityOrPoints() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ?",
                Long.class, me)).isZero();
        assertThat(decisions.countByUserId(me)).isZero();
        assertThat(rewardBudgets.findByUserIdAndWeekStartsOn(me, LocalDate.of(2026, 10, 5))).isEmpty();
        assertThat(rewardBudgets.findByUserIdAndWeekStartsOn(me, LocalDate.of(2026, 10, 12))).isEmpty();
        assertThat(owners.findByUserId(me).orElseThrow().getBalance()).isZero();
    }

    /** 別threadからSpring proxyを通すため、各feedは独立REQUIRES_NEW TXとなる。 */
    private List<FeedAttempt> concurrentFeed(UUID firstKey, UUID secondKey) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CyclicBarrier(2);
        try {
            var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return attemptFeed(firstKey); });
            var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return attemptFeed(secondKey); });
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("care競合試験workerが終了していません");
            }
        }
    }

    private FeedAttempt attemptFeed(UUID key) {
        try {
            return new FeedAttempt(key, feeding.feed(me, key, new RanchVersionRequest("0"), FEED_AT), null);
        } catch (BusinessException failure) {
            return new FeedAttempt(key, null, failure);
        }
    }

    private record FeedAttempt(UUID key, FeedingResult result, BusinessException failure) { }

    @Test
    void fiveFreeFeedingsOnSameDayReachAdultAndSixthGivesZeroXp() {
        UUID firstKey = UUID.randomUUID();
        var first = feeding.feed(me, firstKey, new RanchVersionRequest("0"), FEED_AT);
        assertThat(first.gainedXp()).isEqualTo("20");
        assertThat(first.costPoints()).isEqualTo("0");
        assertThat(first.careKind()).isEqualTo("FREE_BASIC");
        assertThat(first.stageBefore()).isEqualTo(DinosaurStage.BABY);
        var firstLedger = ledger.findByUserIdOrderByOccurredAtDescIdDesc(me);
        assertThat(firstLedger).hasSize(1);
        assertThat(firstLedger.get(0).getCommandId()).isEqualTo(first.commandId());
        assertThat(firstLedger.get(0).getEntryKind()).isEqualTo("CARE");
        assertThat(firstLedger.get(0).getDeltaPoints()).isZero();
        assertThat(firstLedger.get(0).getDeltaXp()).isEqualTo(20);
        for (int index = 1; index < 5; index++) {
            feeding.feed(me, UUID.randomUUID(), new RanchVersionRequest(Integer.toString(index)),
                    FEED_AT.plusSeconds(index));
        }
        var dinosaur = dinosaurs.findByUserId(me).orElseThrow();
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.ADULT);
        assertThat(dinosaur.getXp()).isEqualTo(100);
        assertThat(dinosaur.getAffinity()).isEqualTo(1);
        var budget = budgets.findByUserIdAndWeekStartsOn(me,
                new com.mannschaft.app.ranch.service.RanchCareCalculator()
                        .weekStartsOn(FEED_AT)).orElseThrow();
        assertThat(budget.getAwardedXp()).isEqualTo(100);
        var sixth = feeding.feed(me, UUID.randomUUID(), new RanchVersionRequest("5"),
                FEED_AT.plusSeconds(5));
        assertThat(sixth.gainedXp()).isEqualTo("0");
        assertThat(sixth.isGrowthCapped()).isTrue();
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(6)
                .anySatisfy(entry -> {
                    assertThat(entry.getCommandId()).isEqualTo(sixth.commandId());
                    assertThat(entry.getDeltaXp()).isZero();
                });
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getAffinity()).isEqualTo(1);
        assertThat(affinities.existsByUserIdAndDinosaurIdAndEarnedOnAndKind(me,
                dinosaur.getId(), FEED_AT.atOffset(java.time.ZoneOffset.UTC).toLocalDate(),
                "FEED")).isTrue();
        assertThat(feeding.feed(me, firstKey, new RanchVersionRequest("0"),
                FEED_AT.plusSeconds(10))).isEqualTo(first);
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isEqualTo(6);
        assertThat(commands.countByUserId(me)).isEqualTo(7);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).hasSize(6);
    }

    @Test
    void pausedFeedingIsRejectedWithoutBudgetOrCommand() {
        ownerCommands.pause(me, UUID.randomUUID(), new RanchVersionRequest("0"),
                FEED_AT.minusSeconds(1));
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> feeding.feed(me, UUID.randomUUID(),
                new RanchVersionRequest("1"), FEED_AT))
                .isInstanceOf(BusinessException.class);
        assertThat(budgets.findByUserIdAndWeekStartsOn(me,
                new com.mannschaft.app.ranch.service.RanchCareCalculator()
                        .weekStartsOn(FEED_AT))).isEmpty();
        assertThat(commands.countByUserId(me)).isEqualTo(before);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).isEmpty();
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getXp()).isZero();
    }

    @Test
    void ownerVersionOverflowRollsBackXpAffinityBudgetCommandAndLedger() {
        var owner = owners.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(owner, "version", Long.MAX_VALUE);
        owners.saveAndFlush(owner);
        long beforeCommands = commands.countByUserId(me);

        assertThatThrownBy(() -> feeding.feed(me, UUID.randomUUID(),
                new RanchVersionRequest(Long.toString(Long.MAX_VALUE)), FEED_AT))
                .isInstanceOf(ArithmeticException.class);
        var dinosaur = dinosaurs.findByUserId(me).orElseThrow();
        assertThat(dinosaur.getXp()).isZero();
        assertThat(dinosaur.getAffinity()).isZero();
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.BABY);
        assertThat(owners.findByUserId(me).orElseThrow().getVersion())
                .isEqualTo(Long.MAX_VALUE);
        assertThat(budgets.findByUserIdAndWeekStartsOn(me,
                new com.mannschaft.app.ranch.service.RanchCareCalculator()
                        .weekStartsOn(FEED_AT))).isEmpty();
        assertThat(affinities.existsByUserIdAndDinosaurIdAndEarnedOnAndKind(me,
                dinosaur.getId(), FEED_AT.atOffset(java.time.ZoneOffset.UTC).toLocalDate(),
                "FEED")).isFalse();
        assertThat(commands.countByUserId(me)).isEqualTo(beforeCommands);
        assertThat(ledger.findByUserIdOrderByOccurredAtDescIdDesc(me)).isEmpty();
    }
}
