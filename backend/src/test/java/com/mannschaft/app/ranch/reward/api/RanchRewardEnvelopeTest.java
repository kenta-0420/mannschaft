package com.mannschaft.app.ranch.reward.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 02§3.2 の本人・scope・finite origin/facts をenvelope作成時に固定する。 */
class RanchRewardEnvelopeTest {
    private RanchRewardEnvelope attendance(RanchRewardEnvelope.ScopeType scopeType,
                                             RanchRewardEnvelope.IdType scopeIdType,
                                             String scopeId,
                                             RanchRewardEnvelope.ActorKind actorKind,
                                             Long actorUserId,
                                             Long originalAdminId) {
        return new RanchRewardEnvelope(UUID.randomUUID(), 1,
                RanchRewardSourceType.ATTENDANCE_RESPONSE,
                RanchRewardEnvelope.IdType.LONG, "73", scopeType, scopeIdType, scopeId,
                actorKind, actorUserId, originalAdminId, 21L, 21L,
                Instant.parse("2026-10-05T09:00:00.123456Z"),
                RanchRewardEnvelope.Origin.SELF_RESPONSE,
                new RanchRewardEnvelope.Attendance(RanchRewardEnvelope.AttendanceStatus.ATTENDING,
                        false, true));
    }

    @Test
    void personalScopeHasNoScopeIdAndAdminOriginIsAnIndependentField() {
        var personal = attendance(RanchRewardEnvelope.ScopeType.PERSONAL, null, null,
                RanchRewardEnvelope.ActorKind.USER, 21L, 5L);
        assertThat(personal.canonicalScopeIdBytes()).isNull();
        assertThat(personal.originalAdminId()).isEqualTo(5L);
        assertThatThrownBy(() -> attendance(RanchRewardEnvelope.ScopeType.PERSONAL,
                RanchRewardEnvelope.IdType.LONG, "21", RanchRewardEnvelope.ActorKind.USER, 21L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attendance(RanchRewardEnvelope.ScopeType.TEAM,
                null, null, RanchRewardEnvelope.ActorKind.USER, 21L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actorIdentityAndCanonicalIdsAreStrict() {
        assertThatThrownBy(() -> attendance(RanchRewardEnvelope.ScopeType.ORGANIZATION,
                RanchRewardEnvelope.IdType.LONG, "01", RanchRewardEnvelope.ActorKind.USER, 21L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attendance(RanchRewardEnvelope.ScopeType.TEAM,
                RanchRewardEnvelope.IdType.LONG, "7", RanchRewardEnvelope.ActorKind.USER, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attendance(RanchRewardEnvelope.ScopeType.TEAM,
                RanchRewardEnvelope.IdType.LONG, "7", RanchRewardEnvelope.ActorKind.SYSTEM, 21L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceFactsAndOriginCannotBeRelabeledByConsumer() {
        var valid = attendance(RanchRewardEnvelope.ScopeType.PERSONAL, null, null,
                RanchRewardEnvelope.ActorKind.USER, 21L, null);
        assertThatThrownBy(() -> new RanchRewardEnvelope(valid.eventId(), valid.schemaVersion(),
                valid.sourceType(), valid.sourceIdType(), valid.canonicalSourceId(),
                valid.scopeType(), valid.scopeIdType(), valid.canonicalScopeId(),
                valid.actorKind(), valid.actorUserId(), valid.originalAdminId(),
                valid.subjectUserId(), valid.recipientUserId(), valid.occurredAt(),
                RanchRewardEnvelope.Origin.FIRST_PUBLISH, valid.facts()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RanchRewardEnvelope(valid.eventId(), valid.schemaVersion(),
                valid.sourceType(), valid.sourceIdType(), valid.canonicalSourceId(),
                valid.scopeType(), valid.scopeIdType(), valid.canonicalScopeId(),
                valid.actorKind(), valid.actorUserId(), valid.originalAdminId(),
                valid.subjectUserId(), valid.recipientUserId(), valid.occurredAt(),
                valid.origin(), new RanchRewardEnvelope.Blog(
                        RanchRewardEnvelope.PublicationKind.MANUAL, true)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
