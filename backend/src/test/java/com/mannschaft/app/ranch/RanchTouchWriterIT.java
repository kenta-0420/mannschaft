package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.dto.RanchInteractionRequest;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.repository.RanchAffinityUnitRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchOwnerCommandWriter;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.ranch.service.RanchTouchWriter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 卵・休止時の反応とUTC日＋kind初回だけの親密度を実MySQLで検証する。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchTouchWriterIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    private static final Instant START = Instant.parse("2026-10-04T02:00:00.123456789Z");

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchOwnerCommandWriter ownerCommands;
    @Autowired private RanchTouchWriter touch;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchAffinityUnitRepository affinities;
    @Autowired private RanchCommandRepository commands;
    private Long me;

    @BeforeEach
    void enrollSyntheticEgg() {
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrollment.enroll(me, UUID.randomUUID(), START, PROJECTION);
    }

    @Test
    void eggTouchReactsAndOnlyFirstTouchOnUtcDayChangesAffinity() {
        UUID firstKey = UUID.randomUUID();
        var first = touch.touch(me, firstKey,
                new RanchInteractionRequest(InteractionKind.TOUCH, "0"), START.plusSeconds(1));
        assertThat(first.reactionKey()).isEqualTo("EGG_TOUCH");
        assertThat(first.affinityBand()).isEqualTo("NEUTRAL");
        assertThat(first.affinityChanged()).isTrue();
        var second = touch.touch(me, UUID.randomUUID(),
                new RanchInteractionRequest(InteractionKind.TOUCH, "1"), START.plusSeconds(2));
        assertThat(second.affinityChanged()).isFalse();
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getAffinity()).isEqualTo(1);
        assertThat(affinities.existsByUserIdAndDinosaurIdAndEarnedOnAndKind(me,
                first.dinosaurId(), START.atOffset(ZoneOffset.UTC).toLocalDate(),
                "TOUCH")).isTrue();

        var tomorrow = touch.touch(me, UUID.randomUUID(),
                new RanchInteractionRequest(InteractionKind.TOUCH, "2"),
                START.plusSeconds(86400));
        assertThat(tomorrow.affinityChanged()).isTrue();
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getAffinity()).isEqualTo(2);
        assertThat(touch.touch(me, firstKey,
                new RanchInteractionRequest(InteractionKind.TOUCH, "0"),
                START.plusSeconds(172800))).isEqualTo(first);
        assertThat(commands.countByUserId(me)).isEqualTo(4);
    }

    @Test
    void pausedEggStillRespondsWithoutAffinityChange() {
        ownerCommands.pause(me, UUID.randomUUID(), new RanchVersionRequest("0"),
                START.plusSeconds(1));
        var response = touch.touch(me, UUID.randomUUID(),
                new RanchInteractionRequest(InteractionKind.TOUCH, "1"),
                START.plusSeconds(2));
        assertThat(response.reactionKey()).isEqualTo("EGG_TOUCH");
        assertThat(response.affinityBand()).isEqualTo("NEUTRAL");
        assertThat(response.affinityChanged()).isFalse();
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getAffinity()).isZero();
        assertThat(owners.findByUserId(me).orElseThrow().getStatus())
                .isEqualTo(ParticipationStatus.PAUSED);
    }
}
