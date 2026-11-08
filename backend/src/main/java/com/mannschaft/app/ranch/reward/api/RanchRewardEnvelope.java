package com.mannschaft.app.ranch.reward.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.UUID;

/** Source が本体 commit 時に確定した報酬候補。個人情報の原文は含めない。 */
public record RanchRewardEnvelope(
        UUID eventId,
        int schemaVersion,
        RanchRewardSourceType sourceType,
        IdType sourceIdType,
        String canonicalSourceId,
        ScopeType scopeType,
        IdType scopeIdType,
        String canonicalScopeId,
        ActorKind actorKind,
        Long actorUserId,
        Long originalAdminId,
        Long subjectUserId,
        Long recipientUserId,
        Instant occurredAt,
        Origin origin,
        SourceFacts facts) {

    public RanchRewardEnvelope {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(sourceType);
        Objects.requireNonNull(sourceIdType);
        Objects.requireNonNull(scopeType);
        Objects.requireNonNull(actorKind);
        Objects.requireNonNull(subjectUserId);
        Objects.requireNonNull(recipientUserId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(origin);
        Objects.requireNonNull(facts);
        if (schemaVersion <= 0 || subjectUserId <= 0 || recipientUserId <= 0) {
            throw new IllegalArgumentException("報酬envelopeの版または本人IDが不正です");
        }
        if (!occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("発生時刻はMICROS精度が必要です");
        }
        validateCanonicalId(canonicalSourceId, sourceIdType);
        if (scopeType == ScopeType.PERSONAL) {
            if (scopeIdType != null || canonicalScopeId != null) {
                throw new IllegalArgumentException("PERSONALにはscope IDを設定できません");
            }
        } else {
            if (scopeIdType == null || canonicalScopeId == null) {
                throw new IllegalArgumentException("TEAM/ORGANIZATIONにはscope IDが必要です");
            }
            validateCanonicalId(canonicalScopeId, scopeIdType);
        }
        if ((actorUserId != null && actorUserId <= 0)
                || (subjectUserId != null && subjectUserId <= 0)
                || (originalAdminId != null && originalAdminId <= 0)) {
            throw new IllegalArgumentException("ユーザーIDが不正です");
        }
        if ((actorKind == ActorKind.USER && actorUserId == null)
                || (actorKind == ActorKind.SYSTEM && actorUserId != null)) {
            throw new IllegalArgumentException("actor種別とactor IDが一致しません");
        }
        if (!facts.matches(sourceType)) {
            throw new IllegalArgumentException("報酬源と確定事実の種類が一致しません");
        }
        Origin expectedOrigin = switch (sourceType) {
            case ATTENDANCE_RESPONSE -> Origin.SELF_RESPONSE;
            case TIMELINE_ORIGINAL -> Origin.ORIGINAL;
            case BLOG_FIRST_PUBLISH -> Origin.FIRST_PUBLISH;
            case PERSONAL_RECALL_COMPLETE -> Origin.PERSONAL_COMPLETION;
        };
        if (origin != expectedOrigin) {
            throw new IllegalArgumentException("報酬源とoriginが一致しません");
        }
        if (facts instanceof PersonalRecall recall) {
            var week = occurredAt.atZone(ZoneOffset.UTC).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (!week.equals(recall.completionWeek())) {
                throw new IllegalArgumentException("想起週と発生時刻のUTC週が一致しません");
            }
        }
    }

    public byte[] canonicalSourceIdBytes() {
        return canonicalSourceId.getBytes(StandardCharsets.US_ASCII);
    }

    public byte[] canonicalScopeIdBytes() {
        if (canonicalScopeId == null) return null;
        return canonicalScopeId.getBytes(StandardCharsets.US_ASCII);
    }

    private static void validateCanonicalId(String id, IdType type) {
        Objects.requireNonNull(id);
        if (id.isBlank() || id.length() > 80 || !StandardCharsets.US_ASCII.newEncoder().canEncode(id)) {
            throw new IllegalArgumentException("正準IDは非空ASCII 80byte以内です");
        }
        if (type == IdType.UUID && !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("UUIDは正準小文字形式が必要です");
        }
        if (type == IdType.LONG && (!id.matches("[1-9][0-9]*") || !Long.toString(Long.parseLong(id)).equals(id))) {
            throw new IllegalArgumentException("LONGは正準10進表現が必要です");
        }
    }

    public enum IdType { UUID, LONG }
    public enum ScopeType { PERSONAL, TEAM, ORGANIZATION }
    public enum ActorKind { USER, SYSTEM }
    public enum Origin { SELF_RESPONSE, ORIGINAL, FIRST_PUBLISH, PERSONAL_COMPLETION }
    public enum AttendanceStatus { ATTENDING, PARTIAL, ABSENT }
    public enum PostOrigin { ORIGINAL }
    public enum PublicationKind { MANUAL, BULK, SELF_APPROVAL, EDITOR_APPROVAL, SCHEDULED }

    public sealed interface SourceFacts permits Attendance, Timeline, Blog, PersonalRecall {
        boolean matches(RanchRewardSourceType sourceType);
    }

    public record Attendance(AttendanceStatus responseStatus, boolean proxy, boolean firstQualified)
            implements SourceFacts {
        public Attendance { Objects.requireNonNull(responseStatus); }
        @Override public boolean matches(RanchRewardSourceType sourceType) {
            return sourceType == RanchRewardSourceType.ATTENDANCE_RESPONSE;
        }
    }

    public record Timeline(PostOrigin postOrigin, boolean newPost) implements SourceFacts {
        public Timeline { Objects.requireNonNull(postOrigin); }
        @Override public boolean matches(RanchRewardSourceType sourceType) {
            return sourceType == RanchRewardSourceType.TIMELINE_ORIGINAL;
        }
    }

    public record Blog(PublicationKind publicationKind, boolean firstPublish) implements SourceFacts {
        public Blog { Objects.requireNonNull(publicationKind); }
        @Override public boolean matches(RanchRewardSourceType sourceType) {
            return sourceType == RanchRewardSourceType.BLOG_FIRST_PUBLISH;
        }
    }

    public record PersonalRecall(UUID sessionId, int promptCount, java.time.LocalDate completionWeek,
                                 boolean firstCompletion) implements SourceFacts {
        public PersonalRecall {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(completionWeek);
            if (promptCount <= 0 || completionWeek.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
                throw new IllegalArgumentException("想起確定事実の件数またはUTC週境界が不正です");
            }
        }
        @Override public boolean matches(RanchRewardSourceType sourceType) {
            return sourceType == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE;
        }
    }
}
