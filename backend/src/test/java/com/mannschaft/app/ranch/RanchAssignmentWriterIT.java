package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.service.RanchAssignmentResolver;
import com.mannschaft.app.ranch.service.RanchAssignmentWriter;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 選定writerの本人TX境界のみ検証する。外domain完了根拠の検証はfacade統合で別実証。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchAssignmentWriterIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    private static final Instant NOW = Instant.parse("2026-10-04T02:00:00.123456789Z");
    private static final RanchAssignmentResolver.Selection SYNTHETIC_SELECTION =
            new RanchAssignmentResolver.Selection(AssignmentMethod.HABITAT_RANDOM,
                    Habitat.LAND, "S01", "V1", 1L, "ranch-development-v1",
                    null, "a".repeat(64));

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchAssignmentWriter writer;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchCommandRepository commands;
    private Long me;

    @BeforeEach
    void enrollSyntheticEgg() {
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrollment.enroll(me, UUID.randomUUID(), NOW, PROJECTION);
    }

    @Test
    void selectedEggPersistsOnceAndSavedReplayNeedsNoLiveSelection() {
        UUID key = UUID.randomUUID();
        var request = new RanchAssignmentRequest(AssignmentMethod.HABITAT_RANDOM,
                Habitat.LAND, null, null, "0");
        assertThat(writer.savedReplay(me, key, request)).isEmpty();
        var first = writer.assign(me, key, request, SYNTHETIC_SELECTION,
                NOW.plusSeconds(1));
        assertThat(first.speciesKey()).isEqualTo("S01");
        assertThat(first.version()).isEqualTo("1");
        assertThat(writer.savedReplay(me, key, request)).contains(first);
        assertThat(writer.assign(me, key, request, null, NOW.plusSeconds(100)))
                .isEqualTo(first);
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getSelectionConfirmedAt())
                .isEqualTo(NOW.plusSeconds(1).truncatedTo(
                        java.time.temporal.ChronoUnit.MICROS));
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(commands.countByUserId(me)).isEqualTo(2);
    }

    @Test
    void invalidShapeAndSecondSelectionLeaveCurrentEggAndCommandsUntouched() {
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> writer.assign(me, UUID.randomUUID(),
                new RanchAssignmentRequest(AssignmentMethod.DIAGNOSIS,
                        Habitat.LAND, UUID.randomUUID(), null, "0"),
                SYNTHETIC_SELECTION, NOW.plusSeconds(1)))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.countByUserId(me)).isEqualTo(before);

        writer.assign(me, UUID.randomUUID(),
                new RanchAssignmentRequest(AssignmentMethod.HABITAT_RANDOM,
                        Habitat.LAND, null, null, "0"),
                SYNTHETIC_SELECTION, NOW.plusSeconds(2));
        assertThatThrownBy(() -> writer.assign(me, UUID.randomUUID(),
                new RanchAssignmentRequest(AssignmentMethod.HABITAT_RANDOM,
                        Habitat.LAND, null, null, "1"),
                SYNTHETIC_SELECTION, NOW.plusSeconds(3)))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.countByUserId(me)).isEqualTo(before + 1);
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getSpeciesKey())
                .isEqualTo("S01");
    }
}
