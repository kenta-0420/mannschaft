package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.repository.RanchAffinityUnitRepository;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchFeedingWriter;
import com.mannschaft.app.ranch.service.RanchOwnerCommandWriter;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
