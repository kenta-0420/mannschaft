package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchSettingsRequest;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchParticipationPeriodRepository;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchOwnerCommandWriter;
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

/** 設定と参加期間のcommandを実MySQL取引で検証する。HTTP認証とは別境界。 */
@TestPropertySource(properties = "mannschaft.ranch.development-fixtures=true")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchOwnerCommandWriterIT extends AbstractMySqlIntegrationTest {
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    private static final Instant NOW = Instant.parse("2026-10-04T04:00:00.123456789Z");

    @Autowired private UserRepository users;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchOwnerCommandWriter writer;
    @Autowired private RanchOwnerRepository owners;
    @Autowired private RanchParticipationPeriodRepository periods;
    @Autowired private RanchCommandRepository commands;
    private Long me;

    @BeforeEach
    void enrollSyntheticUser() {
        me = users.saveAndFlush(RanchTestFixture.user()).getId();
        enrollment.enroll(me, UUID.randomUUID(), NOW, PROJECTION);
    }

    @Test
    void settingsChangeUsesVersionAndReplaysSavedResultAfterAnotherChange() {
        UUID key = UUID.randomUUID();
        var requested = new RanchSettingsRequest(RenderStyle.PAINT_2D,
                MotionMode.REDUCED, true, 37, "0");
        var first = writer.settings(me, key, requested, false, NOW.plusSeconds(1));
        assertThat(first.version()).isEqualTo("1");
        assertThat(first.soundVolume()).isEqualTo(37);
        assertThat(first.isVisible()).isFalse();
        assertThat(owners.findByUserId(me).orElseThrow().getRenderStyle())
                .isEqualTo(RenderStyle.PAINT_2D);

        var second = writer.settings(me, UUID.randomUUID(),
                new RanchSettingsRequest(RenderStyle.PIXEL, MotionMode.STOPPED,
                        false, 0, "1"), false, NOW.plusSeconds(2));
        assertThat(second.version()).isEqualTo("2");
        assertThat(writer.settings(me, key, requested, true, NOW.plusSeconds(3)))
                .isEqualTo(first);
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(commands.countByUserId(me)).isEqualTo(3);
    }

    @Test
    void pauseClosesOnePeriodAndResumeOpensOneNewPeriodWithoutReplayWrites() {
        UUID pauseKey = UUID.randomUUID();
        var paused = writer.pause(me, pauseKey, new RanchVersionRequest("0"),
                NOW.plusSeconds(10));
        assertThat(paused.status()).isEqualTo(ParticipationStatus.PAUSED);
        assertThat(paused.version()).isEqualTo("1");
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).isEmpty();
        assertThat(writer.pause(me, pauseKey, new RanchVersionRequest("0"),
                NOW.plusSeconds(20))).isEqualTo(paused);

        var resumed = writer.resume(me, UUID.randomUUID(),
                new RanchVersionRequest("1"), NOW.plusSeconds(30));
        assertThat(resumed.status()).isEqualTo(ParticipationStatus.ACTIVE);
        assertThat(resumed.version()).isEqualTo("2");
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).hasSize(1);
        assertThat(periods.findAll().stream().filter(p -> me.equals(p.getUserId())))
                .hasSize(2);
        assertThat(commands.countByUserId(me)).isEqualTo(3);
    }

    @Test
    void staleVersionAndChangedSameKeyDoNotConsumeCommands() {
        UUID key = UUID.randomUUID();
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> writer.pause(me, key,
                new RanchVersionRequest("7"), NOW.plusSeconds(1)))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.countByUserId(me)).isEqualTo(before);
        assertThat(owners.findByUserId(me).orElseThrow().getStatus())
                .isEqualTo(ParticipationStatus.ACTIVE);

        writer.pause(me, key, new RanchVersionRequest("0"), NOW.plusSeconds(2));
        assertThatThrownBy(() -> writer.resume(me, key,
                new RanchVersionRequest("1"), NOW.plusSeconds(3)))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.countByUserId(me)).isEqualTo(before + 1);
        assertThat(owners.findByUserId(me).orElseThrow().getStatus())
                .isEqualTo(ParticipationStatus.PAUSED);
    }

    @Test
    void pauseAtParticipationStartIsConflictWithoutPeriodOrCommandChange() {
        UUID key = UUID.randomUUID();
        long before = commands.countByUserId(me);
        assertThatThrownBy(() -> writer.pause(me, key,
                new RanchVersionRequest("0"), NOW))
                .isInstanceOf(BusinessException.class);
        assertThat(owners.findByUserId(me).orElseThrow().getStatus())
                .isEqualTo(ParticipationStatus.ACTIVE);
        assertThat(owners.findByUserId(me).orElseThrow().getVersion()).isZero();
        assertThat(periods.findByUserIdAndEndsAtIsNull(me)).hasSize(1)
                .allSatisfy(period -> assertThat(period.getEndsAt()).isNull());
        assertThat(commands.countByUserId(me)).isEqualTo(before);
    }
}
