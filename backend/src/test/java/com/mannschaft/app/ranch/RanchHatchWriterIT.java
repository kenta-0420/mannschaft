package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.HatchResponse;
import com.mannschaft.app.ranch.dto.RanchHatchRequest;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchHatchWriter;
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

/** 永久名と孵化の原子保存、新キー同名の現在状態保存を実MySQLで検証する。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchHatchWriterIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    private static final Instant START = Instant.parse("2026-10-04T02:00:00.123456789Z");
    private static final Instant READY = START.plusSeconds(604800);

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchHatchWriter hatch;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchCommandRepository commands;
    private Long me;

    @BeforeEach
    void enrollAndSelectSyntheticEgg() {
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrollment.enroll(me, UUID.randomUUID(), START, PROJECTION);
        var dinosaur = dinosaurs.findByUserId(me).orElseThrow();
        ReflectionTestUtils.setField(dinosaur, "habitat", Habitat.LAND);
        ReflectionTestUtils.setField(dinosaur, "speciesKey", "S01");
        ReflectionTestUtils.setField(dinosaur, "variantKey", "V1");
        ReflectionTestUtils.setField(dinosaur, "speciesCatalogVersion", 1L);
        ReflectionTestUtils.setField(dinosaur, "assignmentMethod", AssignmentMethod.HABITAT_RANDOM);
        ReflectionTestUtils.setField(dinosaur, "selectionConfirmedAt", START.plusSeconds(1));
        dinosaurs.saveAndFlush(dinosaur);
    }

    @Test
    void confirmedReadyHatchSavesPermanentNameAndReplaysOriginalResult() {
        UUID key = UUID.randomUUID();
        var request = new RanchHatchRequest("0", "  テスト  ", true);
        var first = hatch.hatch(me, key, request, READY, PROJECTION);
        assertThat(first.kind()).isEqualTo(HatchResponse.Kind.HATCH_RESULT);
        assertThat(first.result().stage()).isEqualTo(DinosaurStage.BABY);
        assertThat(first.result().name()).isEqualTo("テスト");
        assertThat(first.result().commandId()).isNotNull();
        assertThat(commands.findByUserIdAndIdempotencyKey(me, key).orElseThrow().getId())
                .isEqualTo(first.result().commandId());
        assertThat(first.result().hatchedAt()).isEqualTo(READY.truncatedTo(
                java.time.temporal.ChronoUnit.MICROS));
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getName()).isEqualTo("テスト");
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(hatch.hatch(me, key, request, READY.plusSeconds(100), null))
                .isEqualTo(first);
        assertThat(commands.countByUserId(me)).isEqualTo(2);
    }

    @Test
    void newKeySameNameGetsCurrentStateWhileOtherNameConflicts() {
        hatch.hatch(me, UUID.randomUUID(), new RanchHatchRequest("0", "テスト", true),
                READY, PROJECTION);
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> hatch.hatch(me, UUID.randomUUID(),
                new RanchHatchRequest("1", "別名", true), READY.plusSeconds(1),
                PROJECTION)).isInstanceOf(BusinessException.class);
        assertThat(commands.countByUserId(me)).isEqualTo(before);

        UUID sameNameKey = UUID.randomUUID();
        var current = hatch.hatch(me, sameNameKey,
                new RanchHatchRequest("1", "テスト", true), READY.plusSeconds(2),
                PROJECTION);
        assertThat(current.kind()).isEqualTo(HatchResponse.Kind.CURRENT_STATE);
        assertThat(current.state().dinosaur().name()).isEqualTo("テスト");
        assertThat(hatch.hatch(me, sameNameKey,
                new RanchHatchRequest("1", "テスト", true), READY.plusSeconds(100),
                null)).isEqualTo(current);
        assertThat(commands.countByUserId(me)).isEqualTo(before + 1);
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void unreadyEggOrMissingConfirmationDoesNotStoreNameOrCommand() {
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> hatch.hatch(me, UUID.randomUUID(),
                new RanchHatchRequest("0", "テスト", true), READY.minusSeconds(1),
                PROJECTION)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> hatch.hatch(me, UUID.randomUUID(),
                new RanchHatchRequest("0", "テスト", false), READY,
                PROJECTION)).isInstanceOf(BusinessException.class);
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getName()).isNull();
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getStage())
                .isEqualTo(DinosaurStage.EGG);
        assertThat(commands.countByUserId(me)).isEqualTo(before);
    }
}
