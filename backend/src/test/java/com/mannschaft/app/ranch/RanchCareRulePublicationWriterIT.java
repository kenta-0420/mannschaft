package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchCareRulePublicationRequest;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchCareRuleRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.service.RanchCareRulePublicationWriter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQLでcare版と固定ACKの同一TXを検証する。HTTPのfresh管理者認可とは別の証明。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchCareRulePublicationWriterIT extends AbstractMySqlIntegrationTest {
    @Autowired RanchCareRulePublicationWriter writer;
    @Autowired RanchCareRuleRepository rules;
    @Autowired RanchAdminCommandRepository commands;
    @Autowired RanchOperationalControlRepository controls;
    @Autowired UserRepository users;
    private Long actorId;
    private final Instant now = Instant.parse("2026-10-05T00:00:00.123456Z");

    @BeforeEach
    void syntheticActorAndMigrationEquivalentSingleton() {
        actorId = users.saveAndFlush(RanchTestFixture.user()).getId();
        // test profileはFlyway無効。seedの再現はfixture内だけで行い、production GETには置かない。
        if (controls.findById(1).isEmpty()) {
            controls.saveAndFlush(RanchOperationalControlEntity.builder().id(1).careEnabled(false)
                    .shopEnabled(false).deliveryPaused(true).version(0).createdAt(now).updatedAt(now).build());
        }
    }

    @Test
    void replayPreservesAckAfterEffectiveTimeAndRejectsDifferentBody() {
        UUID key = UUID.randomUUID();
        var request = request("2031-01-06T00:00:00Z", "20");
        var first = writer.publish(actorId, key, request, now);
        var replay = writer.publish(actorId, key, request, Instant.parse("2031-01-07T00:00:00Z"));
        assertThat(first.createdNow()).isTrue();
        assertThat(replay.createdNow()).isFalse();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(rules.findById(first.response().id()).orElseThrow().getContentHash()).hasSize(32);
        assertThatThrownBy(() -> writer.publish(actorId, key, request("2031-01-06T00:00:00Z", "21"), now))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actorId, key).orElseThrow().getCompletedAt()).isEqualTo(now);
        assertThat(rules.findById(first.response().id()).orElseThrow().getAmountXp()).isEqualTo(20L);
    }

    @Test
    void rejectedPublicationDoesNotSaveCommand() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> writer.publish(actorId, key, request("2026-10-05T00:00:00Z", "20"), now))
                .isInstanceOf(BusinessException.class);
        assertThat(commands.findByActorUserIdAndIdempotencyKey(actorId, key)).isEmpty();
    }

    private RanchCareRulePublicationRequest request(String at, String amount) {
        return new RanchCareRulePublicationRequest(Instant.parse(at), amount, "100", "60", "100", "CARE_RULE_TEST");
    }
}
