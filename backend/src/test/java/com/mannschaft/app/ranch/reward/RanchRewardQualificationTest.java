package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 源が捕捉した初回事実を尊重し、代理/履歴不明を現在状態から復元しない。 */
class RanchRewardQualificationTest {
    private RanchRewardEnvelope event(RanchRewardSourceType source,
                                      RanchRewardEnvelope.ActorKind actorKind, Long actor,
                                      Long originalAdmin, Long subject, Long recipient,
                                      RanchRewardEnvelope.Origin origin,
                                      RanchRewardEnvelope.SourceFacts facts) {
        return new RanchRewardEnvelope(UUID.randomUUID(), 1, source,
                RanchRewardEnvelope.IdType.UUID, UUID.randomUUID().toString(),
                RanchRewardEnvelope.ScopeType.PERSONAL, null, null,
                actorKind, actor, originalAdmin, subject, recipient,
                Instant.parse("2026-10-05T09:00:00.123456Z"), origin, facts);
    }

    @Test
    void attendanceRequiresCapturedFirstSelfAnswerAndNoImpersonation() {
        var first = event(RanchRewardSourceType.ATTENDANCE_RESPONSE,
                RanchRewardEnvelope.ActorKind.USER, 21L, null, 21L, 21L,
                RanchRewardEnvelope.Origin.SELF_RESPONSE,
                new RanchRewardEnvelope.Attendance(RanchRewardEnvelope.AttendanceStatus.ABSENT,
                        false, true));
        assertThat(RanchRewardQualification.eligible(first)).isTrue();
        assertThat(RanchRewardQualification.eligible(event(RanchRewardSourceType.ATTENDANCE_RESPONSE,
                RanchRewardEnvelope.ActorKind.USER, 21L, null, 21L, 21L,
                RanchRewardEnvelope.Origin.SELF_RESPONSE,
                new RanchRewardEnvelope.Attendance(RanchRewardEnvelope.AttendanceStatus.ABSENT,
                        true, true)))).isFalse();
        assertThat(RanchRewardQualification.eligible(event(RanchRewardSourceType.ATTENDANCE_RESPONSE,
                RanchRewardEnvelope.ActorKind.USER, 21L, 99L, 21L, 21L,
                RanchRewardEnvelope.Origin.SELF_RESPONSE, first.facts()))).isFalse();
        assertThat(RanchRewardQualification.eligible(event(RanchRewardSourceType.ATTENDANCE_RESPONSE,
                RanchRewardEnvelope.ActorKind.USER, 21L, null, 21L, 22L,
                RanchRewardEnvelope.Origin.SELF_RESPONSE, first.facts()))).isFalse();
    }

    @Test
    void blogEditorOrScheduledSystemCanRewardAuthorOnlyOnFirstPublication() {
        var first = new RanchRewardEnvelope.Blog(RanchRewardEnvelope.PublicationKind.EDITOR_APPROVAL,
                true);
        assertThat(RanchRewardQualification.eligible(event(RanchRewardSourceType.BLOG_FIRST_PUBLISH,
                RanchRewardEnvelope.ActorKind.USER, 99L, null, 21L, 21L,
                RanchRewardEnvelope.Origin.FIRST_PUBLISH, first))).isTrue();
        assertThat(RanchRewardQualification.eligible(event(RanchRewardSourceType.BLOG_FIRST_PUBLISH,
                RanchRewardEnvelope.ActorKind.SYSTEM, null, null, 21L, 21L,
                RanchRewardEnvelope.Origin.FIRST_PUBLISH,
                new RanchRewardEnvelope.Blog(RanchRewardEnvelope.PublicationKind.SCHEDULED,
                        true)))).isTrue();
        assertThat(RanchRewardQualification.eligible(event(RanchRewardSourceType.BLOG_FIRST_PUBLISH,
                RanchRewardEnvelope.ActorKind.USER, 99L, null, 21L, 21L,
                RanchRewardEnvelope.Origin.FIRST_PUBLISH,
                new RanchRewardEnvelope.Blog(RanchRewardEnvelope.PublicationKind.EDITOR_APPROVAL,
                        false)))).isFalse();
    }

    @Test
    void originalTimelineAndCompletedRecallNeedCapturedFirstEvent() {
        assertThat(RanchRewardQualification.eligible(event(RanchRewardSourceType.TIMELINE_ORIGINAL,
                RanchRewardEnvelope.ActorKind.USER, 21L, null, 21L, 21L,
                RanchRewardEnvelope.Origin.ORIGINAL,
                new RanchRewardEnvelope.Timeline(RanchRewardEnvelope.PostOrigin.ORIGINAL, true))))
                .isTrue();
        assertThat(RanchRewardQualification.eligible(event(RanchRewardSourceType.TIMELINE_ORIGINAL,
                RanchRewardEnvelope.ActorKind.USER, 21L, null, 21L, 21L,
                RanchRewardEnvelope.Origin.ORIGINAL,
                new RanchRewardEnvelope.Timeline(RanchRewardEnvelope.PostOrigin.ORIGINAL, false))))
                .isFalse();
        var recall = new RanchRewardEnvelope.PersonalRecall(UUID.randomUUID(), 3,
                LocalDate.parse("2026-10-05"), true);
        assertThat(RanchRewardQualification.eligible(event(
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                RanchRewardEnvelope.ActorKind.USER, 21L, null, 21L, 21L,
                RanchRewardEnvelope.Origin.PERSONAL_COMPLETION, recall))).isTrue();
        assertThat(RanchRewardQualification.eligible(event(
                RanchRewardSourceType.PERSONAL_RECALL_COMPLETE,
                RanchRewardEnvelope.ActorKind.USER, 21L, null, 21L, 21L,
                RanchRewardEnvelope.Origin.PERSONAL_COMPLETION,
                new RanchRewardEnvelope.PersonalRecall(recall.sessionId(), 3,
                        recall.completionWeek(), false)))).isFalse();
    }
}
