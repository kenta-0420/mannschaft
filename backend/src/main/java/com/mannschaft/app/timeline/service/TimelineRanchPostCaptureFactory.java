package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.TimelineContentFingerprint;
import com.mannschaft.app.timeline.dto.TimelineRanchRewardPayload;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 既存業務TX内のpure捕捉。本文を複製せず、報酬専用SQLを書かない。 */
@Component
@RequiredArgsConstructor
class TimelineRanchPostCaptureFactory {
    private final Clock clock;
    private final TimelineContentFingerprintService fingerprints;
    private final TimelineRanchCaptureTelemetry telemetry;
    TimelineContentFingerprint prepare(TimelinePostEntity post, CreatePostRequest request, Long actor) {
        if (!request.isRanchCaptureArmed()) return null;
        try {
            if (post.getStatus() != PostStatus.PUBLISHED || post.getParentId() != null
                    || post.getRepostOfId() != null || post.getPostedAsType() != PostedAsType.USER
                    || post.getScopeType() == PostScopeType.VILLAGE || request.getPoll() != null
                    || (request.getAttachments() != null && !request.getAttachments().isEmpty())
                    || post.getContent() == null || post.getContent().length() > 5000) return null;
            var at = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var fingerprint = fingerprints.fingerprint(actor, at, post.getContent(), List.of());
            post.freezeRanchOriginalMetadata(at, actor);
            return fingerprint;
        } catch (RuntimeException ignored) {
            telemetry.lost(TimelineRanchCaptureTelemetry.Reason.CAPTURE_FAILED);
            return null;
        }
    }
    void finish(TimelinePostEntity post, CreatePostRequest request, Long actor, TimelineContentFingerprint fingerprint) {
        if (fingerprint == null || !request.isRanchCaptureArmed()) return;
        try {
            var scope = post.getScopeType() == PostScopeType.TEAM ? RanchRewardEnvelope.ScopeType.TEAM
                    : post.getScopeType() == PostScopeType.ORGANIZATION ? RanchRewardEnvelope.ScopeType.ORGANIZATION
                    : RanchRewardEnvelope.ScopeType.PERSONAL;
            var scopeId = scope == RanchRewardEnvelope.ScopeType.PERSONAL ? null : post.getScopeId();
            var payload = new TimelineRanchRewardPayload(UuidV7.generate(), 1, RanchRewardSourceType.TIMELINE_ORIGINAL,
                    RanchRewardEnvelope.IdType.LONG, post.getId().toString(), scope,
                    scopeId == null ? null : RanchRewardEnvelope.IdType.LONG,
                    scopeId == null ? null : scopeId.toString(), RanchRewardEnvelope.ActorKind.USER,
                    actor, null, actor, actor, post.getRanchQualifiedAt(), RanchRewardEnvelope.Origin.ORIGINAL,
                    new RanchRewardEnvelope.Timeline(RanchRewardEnvelope.PostOrigin.ORIGINAL, true));
            request.recordRanchCapture(payload, fingerprint);
        } catch (RuntimeException ignored) {
            telemetry.lost(TimelineRanchCaptureTelemetry.Reason.CAPTURE_FAILED);
        }
    }
}
