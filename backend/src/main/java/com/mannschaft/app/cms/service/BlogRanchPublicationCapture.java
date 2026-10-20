package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.PublicationKind;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 源TXのcurrent前状態と最終公開状態から、保護中authorの一回だけを捕捉する。 */
@Component
@RequiredArgsConstructor
class BlogRanchPublicationCapture {
    private final BlogRanchCaptureFactory captures;
    private final BlogRanchCaptureTelemetry telemetry;
    private final Clock clock;
    void capture(BlogPostEntity post, Long actor, BlogRanchCaptureContext context, PublicationKind kind) {
        if(context==null || !context.qualified() || !Objects.equals(context.protectedAuthor(),post.getAuthorId())
                || !post.isPublicationHistoryKnown() || post.isRanchPublicationHistorical()
                || post.isRanchPublicationObserved() || post.getStatus()!=PostStatus.PUBLISHED) return;
        try {
            var at=clock.instant().truncatedTo(ChronoUnit.MICROS);
            var candidate=captures.capture(post,actor,at,kind);
            if(post.freezeRanchFirstPublicationMetadata(at,context.protectedAuthor())) context.record(candidate);
            else telemetry.lost(BlogRanchCaptureTelemetry.Reason.CAPTURE_FAILED);
        } catch(RuntimeException ignored) { telemetry.lost(BlogRanchCaptureTelemetry.Reason.CAPTURE_FAILED); }
    }
}
