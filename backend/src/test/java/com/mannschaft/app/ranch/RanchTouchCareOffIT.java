package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.ranch.dto.RanchInteractionRequest;
import com.mannschaft.app.ranch.entity.RanchDinosaurEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.repository.RanchAffinityUnitRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.service.RanchTouchWriter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 本番既定care OFFでも卵の短い反応だけを返し、親密度を増やさない。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=false")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchTouchCareOffIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T02:00:00.123456Z");

    @Autowired private UserRepository users;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchAffinityUnitRepository affinities;
    @Autowired private RanchCommandRepository commands;
    @Autowired private RanchTouchWriter touch;

    @Test
    void touchWhenCareIsOffRespondsButCreatesNoAffinityUnit() {
        Long me = users.saveAndFlush(RanchTestFixture.user()).getId();
        RanchOwnerEntity owner = owners.saveAndFlush(RanchOwnerEntity.builder()
                .userId(me).status(ParticipationStatus.ACTIVE).balance(0)
                .viewMode("ROOM").renderStyle(RenderStyle.PIXEL)
                .motionMode(MotionMode.NORMAL).soundEnabled(false).soundVolume(100)
                .version(0).createdAt(NOW).build());
        RanchDinosaurEntity dinosaur = dinosaurs.saveAndFlush(RanchDinosaurEntity.builder()
                .ownerId(owner.getId()).userId(me).eggStartedAt(NOW)
                .eggReadyAt(NOW.plusSeconds(604800))
                .eggRuleSnapshot("{\"ruleVersion\":\"offline-test\",\"durationSeconds\":604800,\"smallCrackSeconds\":259200,\"wideCrackSeconds\":432000}")
                .growthRuleSnapshot("{\"ruleVersion\":\"offline-test\",\"juvenileXp\":60,\"adultXp\":100}")
                .affinityRuleSnapshot("{\"ruleVersion\":\"offline-test\",\"gain\":1,\"warmAffinity\":3,\"closeAffinity\":6}")
                .stage(DinosaurStage.EGG).xp(0).affinity(0).version(0)
                .createdAt(NOW).build());

        var result = touch.touch(me, UUID.randomUUID(),
                new RanchInteractionRequest(InteractionKind.TOUCH, "0"), NOW.plusSeconds(1));
        assertThat(result.reactionKey()).isEqualTo("EGG_TOUCH");
        assertThat(result.affinityBand()).isEqualTo("NEUTRAL");
        assertThat(result.affinityChanged()).isFalse();
        assertThat(dinosaurs.findByUserId(me).orElseThrow().getAffinity()).isZero();
        assertThat(affinities.existsByUserIdAndDinosaurIdAndEarnedOnAndKind(me,
                dinosaur.getId(), NOW.atOffset(java.time.ZoneOffset.UTC).toLocalDate(),
                "TOUCH")).isFalse();
        assertThat(commands.countByUserId(me)).isEqualTo(1);
    }
}
