package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;

import java.util.Objects;

/** 源で凍結されたfactの型とactor境界を再確認し、現在状態から資格を推測しない。 */
public final class RanchRewardQualification {
    private RanchRewardQualification() { }

    public static boolean eligible(RanchRewardEnvelope event) {
        Objects.requireNonNull(event);
        if (event.originalAdminId() != null
                || !event.subjectUserId().equals(event.recipientUserId())) {
            return false;
        }
        return switch (event.sourceType()) {
            case ATTENDANCE_RESPONSE -> event.actorKind() == RanchRewardEnvelope.ActorKind.USER
                    && event.actorUserId().equals(event.subjectUserId())
                    && event.facts() instanceof RanchRewardEnvelope.Attendance fact
                    && !fact.proxy() && fact.firstQualified();
            case TIMELINE_ORIGINAL -> event.actorKind() == RanchRewardEnvelope.ActorKind.USER
                    && event.actorUserId().equals(event.subjectUserId())
                    && event.facts() instanceof RanchRewardEnvelope.Timeline fact
                    && fact.postOrigin() == RanchRewardEnvelope.PostOrigin.ORIGINAL
                    && fact.newPost();
            case BLOG_FIRST_PUBLISH -> event.facts() instanceof RanchRewardEnvelope.Blog fact
                    && fact.firstPublish();
            case PERSONAL_RECALL_COMPLETE -> event.actorKind() == RanchRewardEnvelope.ActorKind.USER
                    && event.actorUserId().equals(event.subjectUserId())
                    && event.facts() instanceof RanchRewardEnvelope.PersonalRecall fact
                    && fact.firstCompletion();
        };
    }
}
