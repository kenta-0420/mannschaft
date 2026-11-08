package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.ranch.service.RanchStateReader;
import com.mannschaft.app.ranch.service.RanchRuleProvider;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchParticipationPeriodRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** writer内部のMySQL原子性。公開HTTPのauth guard証明とは別。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchEnrollmentWriterIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection TEST_PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    @Autowired private RanchEnrollmentWriter writer;
    @Autowired private RanchStateReader reader;
    @Autowired private UserRepository users;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchRoomPlacementRepository slots;
    @Autowired private RanchParticipationPeriodRepository periods;
    @Autowired private RanchCommandRepository commands;
    @Autowired private RanchCareWeekBudgetRepository careBudgets;
    @Autowired private RanchStateAssembler states;
    @Autowired private ObjectMapper json;
    private Long me;

    @BeforeEach
    void createSyntheticUser() {
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
    }

    @Test
    void enrollmentCreatesOneEggPeriodAndThreeSlotsThenReplaysWithoutWrites() throws Exception {
        UUID key = UUID.randomUUID();
        Instant requested = Instant.parse("2026-10-04T02:00:00.123456789Z");

        var first = writer.enroll(me, key, requested, TEST_PROJECTION);
        assertThat(first.createdNow()).isTrue();
        var owner = owners.findByUserId(me).orElseThrow();
        assertThat(first.ownerId()).isEqualTo(owner.getId());
        assertThat(owner.getMotionMode()).isEqualTo(MotionMode.REDUCED);
        assertThat(owner.isSoundEnabled()).isFalse();
        assertThat(owner.getSoundVolume()).isEqualTo(50);
        assertThat(first.snapshot().settings().motionMode()).isEqualTo(MotionMode.REDUCED);
        assertThat(first.snapshot().settings().isSoundEnabled()).isFalse();
        assertThat(first.snapshot().settings().soundVolume()).isEqualTo(50);
        var dinosaur = dinosaurs.findByUserId(me).orElseThrow();
        assertThat(dinosaur.getId()).isEqualTo(first.dinosaurId());
        assertThat(dinosaur.getStage()).isEqualTo(DinosaurStage.EGG);
        assertThat(dinosaur.getEggStartedAt())
                .isEqualTo(Instant.parse("2026-10-04T02:00:00.123456Z"));
        assertThat(dinosaur.getEggReadyAt())
                .isEqualTo(dinosaur.getEggStartedAt().plusSeconds(604800));
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3)
                .allSatisfy(slot -> assertThat(slot.getInventoryId()).isNull());
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).hasSize(1);
        assertThat(commands.findByUserIdAndIdempotencyKey(me, key)).isPresent();
        assertThat(first.snapshot().serverTime())
                .isEqualTo(Instant.parse("2026-10-04T02:00:00.123456Z"));
        assertThat(first.snapshot().roomSlots()).hasSize(3);
        assertThat(first.snapshot().dinosaur().stage()).isEqualTo(DinosaurStage.EGG);
        var savedResult = commands.findByUserIdAndIdempotencyKey(me, key).orElseThrow();
        var savedTree = json.readTree(savedResult.getResultJson());

        var replay = writer.enroll(me, key, requested.plusSeconds(100), null);
        assertThat(replay.createdNow()).isFalse();
        assertThat(replay.ownerId()).isEqualTo(first.ownerId());
        assertThat(replay.dinosaurId()).isEqualTo(first.dinosaurId());
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.snapshot()).isEqualTo(first.snapshot());
        assertThat(json.readTree(commands.findByUserIdAndIdempotencyKey(me, key)
                .orElseThrow().getResultJson())).isEqualTo(savedTree);

        ReflectionTestUtils.setField(owner, "soundVolume", 37);
        owners.saveAndFlush(owner);
        assertThat(reader.read(me, requested.plusSeconds(200), TEST_PROJECTION)
                .settings().soundVolume()).isEqualTo(37);
        var replayAfterStateChange = writer.enroll(me, key, requested.plusSeconds(300), null);
        assertThat(replayAfterStateChange.snapshot()).isEqualTo(first.snapshot());
        assertThat(json.readTree(commands.findByUserIdAndIdempotencyKey(me, key)
                .orElseThrow().getResultJson())).isEqualTo(savedTree);
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3);
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).hasSize(1);
    }

    @Test
    void aDifferentKeyForExistingOwnerKeepsSameDinosaurAndRecordsSuccess() {
        var first = writer.enroll(me, UUID.randomUUID(), Instant.now(), TEST_PROJECTION);
        UUID secondKey = UUID.randomUUID();

        var second = writer.enroll(me, secondKey, Instant.now(), TEST_PROJECTION);
        assertThat(second.createdNow()).isFalse();
        assertThat(second.ownerId()).isEqualTo(first.ownerId());
        assertThat(second.dinosaurId()).isEqualTo(first.dinosaurId());
        assertThat(commands.findByUserIdAndIdempotencyKey(me, secondKey)).isPresent();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).hasSize(3);
    }

    @Test
    void primaryReaderDoesNotEnrollAndSeesCommittedOwnState() {
        Instant now = Instant.parse("2026-10-04T02:00:00.123456789Z");
        var before = reader.read(me, now, TEST_PROJECTION);
        assertThat(before.owner()).isNull();
        assertThat(before.dinosaur()).isNull();
        assertThat(before.assignment()).isNull();
        assertThat(before.roomSlots()).isEmpty();
        assertThat(owners.findByUserId(me)).isEmpty();
        assertThat(dinosaurs.findByUserId(me)).isEmpty();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).isEmpty();
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).isEmpty();
        UUID absentKey = UUID.randomUUID();
        assertThat(commands.findByUserIdAndIdempotencyKey(me, absentKey)).isEmpty();
        assertThat(commands.countByUserId(me)).isZero();

        var created = writer.enroll(me, UUID.randomUUID(), now, TEST_PROJECTION);
        var after = reader.read(me, now.plusSeconds(1), TEST_PROJECTION);
        assertThat(after.owner().id()).isEqualTo(created.ownerId());
        assertThat(after.dinosaur().id()).isEqualTo(created.dinosaurId());
        assertThat(after.roomSlots()).hasSize(3);
        assertThat(owners.findByUserId(me)).isPresent();
    }

    @Test
    void equalGrowthThresholdRuleFailsBeforeAnyRanchRowsAreSaved() {
        RanchRuleProvider invalidRules = new RanchRuleProvider() {
            @Override public boolean careEnabled() { return true; }
            @Override public Optional<CareRuleSnapshot> currentCareRule(Instant now) {
                return Optional.empty();
            }
            @Override public Optional<EggRuleSnapshot> currentEggRule(Instant now) {
                return Optional.of(new EggRuleSnapshot("invalid-equal-growth", 604800,
                        259200, 432000, 100, 100, 1, 3, 6));
            }
        };
        // 規則検証はDMLより前。内部writerの実repoで保存0を確認する。
        RanchEnrollmentWriter invalidWriter = new RanchEnrollmentWriter(owners,
                dinosaurs, slots, periods, careBudgets, commands, invalidRules, states, json);
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> invalidWriter.enroll(me, key, Instant.now(), TEST_PROJECTION))
                .isInstanceOf(BusinessException.class);
        assertThat(owners.findByUserId(me)).isEmpty();
        assertThat(dinosaurs.findByUserId(me)).isEmpty();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).isEmpty();
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).isEmpty();
        assertThat(commands.findByUserIdAndIdempotencyKey(me, key)).isEmpty();
        assertThat(commands.countByUserId(me)).isZero();
    }

    @Test
    void failureAfterEntityCreationRollsBackAllFiveRanchTables() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> writer.enroll(me, key, Instant.now(), null))
                .isInstanceOf(NullPointerException.class);
        // writerのREQUIRES_NEWがrollback済み。以下のrepo呼出は別PRIMARY TX。
        assertThat(owners.findByUserId(me)).isEmpty();
        assertThat(dinosaurs.findByUserId(me)).isEmpty();
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).isEmpty();
        assertThat(slots.findByUserIdOrderBySlotKey(me)).isEmpty();
        assertThat(commands.findByUserIdAndIdempotencyKey(me, key)).isEmpty();
        assertThat(commands.countByUserId(me)).isZero();
    }
}
