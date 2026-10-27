package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Source IDとUTC週の再送同一性、byte衝突時の比較境界を確認する。 */
class RanchRewardCanonicalKeyTest {
    private RanchRewardEnvelope recall(String entryId, UUID sessionId, long recipient,
                                        Instant occurredAt, LocalDate completionWeek) {
        return new RanchRewardEnvelope(UUID.randomUUID(), 1,
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                RanchRewardEnvelope.IdType.UUID, entryId,
                RanchRewardEnvelope.ScopeType.PERSONAL,
                null, null,
                RanchRewardEnvelope.ActorKind.USER, recipient, null, recipient, recipient,
                occurredAt,
                RanchRewardEnvelope.Origin.PERSONAL_COMPLETION,
                new RanchRewardEnvelope.PersonalRecall(sessionId, 4, completionWeek, true));
    }

    @Test
    void repeatEventUsesSameCanonicalBytesAndWeekChangesOnlyRecallKey() {
        String entryId = "018c6fd0-08ec-7b42-a775-c9ccfac5e991";
        UUID firstSession = UUID.fromString("018c6fd0-08ec-7b42-a775-c9ccfac5e992");
        var firstFact = recall(entryId, firstSession, 21,
                Instant.parse("2026-10-05T09:00:00.123456Z"), LocalDate.parse("2026-10-05"));
        var first = RanchRewardCanonicalKey.of(firstFact);
        var retry = RanchRewardCanonicalKey.of(firstFact);
        var nextWeek = RanchRewardCanonicalKey.of(recall(entryId,
                UUID.fromString("018c6fd0-08ec-7b42-a775-c9ccfac5e993"), 21,
                Instant.parse("2026-10-12T09:00:00.123456Z"), LocalDate.parse("2026-10-12")));
        assertThat(first.bytes()).isEqualTo(retry.bytes());
        assertThat(first.sha256()).isEqualTo(retry.sha256());
        assertThat(nextWeek.bytes()).isNotEqualTo(first.bytes());
        assertThat(new String(first.bytes(), StandardCharsets.US_ASCII))
                .endsWith(":USER:21:WEEK:2026-10-05");
    }

    @Test
    void storedByteIdentityAndCanonicalInputAreStrict() {
        String entryId = "018c6fd0-08ec-7b42-a775-c9ccfac5e991";
        UUID sessionId = UUID.fromString("018c6fd0-08ec-7b42-a775-c9ccfac5e992");
        var key = RanchRewardCanonicalKey.of(recall(entryId, sessionId, 21,
                Instant.parse("2026-10-05T09:00:00.123456Z"), LocalDate.parse("2026-10-05")));
        byte[] exposed = key.bytes();
        exposed[0] ^= 1;
        assertThat(key.sameStoredIdentity(exposed)).isFalse();
        assertThat(key.sameStoredIdentity(key.bytes())).isTrue();
        assertThatThrownBy(() -> recall(entryId, sessionId, 21,
                Instant.parse("2026-10-05T09:00:00.123456Z"), LocalDate.parse("2026-10-12")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recall(entryId.toUpperCase(), sessionId, 21,
                Instant.parse("2026-10-05T09:00:00.123456Z"), LocalDate.parse("2026-10-05")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
