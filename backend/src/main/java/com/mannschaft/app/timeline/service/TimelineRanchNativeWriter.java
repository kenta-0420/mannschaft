package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.TimelineRanchRewardPayload;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本文だけの本人PUBLIC/PERSONAL新規保存。同じ本体INSERTにnative時点証拠を固定する。 */
@Service
@RequiredArgsConstructor
class TimelineRanchNativeWriter {
    private final TimelinePostRepository posts;
    private final TimelineMapper mapper;
    private final DomainEventPublisher events;
    private final TimelineContentFingerprintService fingerprints;
    private final TimelineRanchCaptureTelemetry telemetry;
    private final Clock clock;
    record Outcome(PostResponse response,TimelineRanchCapture capture) { }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    Outcome create(CreatePostRequest request,Long resolvedScopeId,Long actor) {
        var scope=PostScopeType.valueOf(request.getScopeTypeOrDefault());
        if(scope!=PostScopeType.PUBLIC && scope!=PostScopeType.PERSONAL)
            throw new BusinessException(CommonErrorCode.COMMON_002);
        if(scope==PostScopeType.PERSONAL && !actor.equals(resolvedScopeId))
            throw new BusinessException(CommonErrorCode.COMMON_002);
        if(request.getContent()==null || request.getContent().isBlank())
            throw new BusinessException(TimelineErrorCode.EMPTY_POST_CONTENT);
        var at=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var post=TimelinePostEntity.builder().scopeType(scope).scopeId(resolvedScopeId==null?0L:resolvedScopeId)
                .deliveryScope(request.getDeliveryScopeOrDefault()).userId(actor).postedAsType(PostedAsType.USER)
                .postedAsId(request.getPostedAsId()).content(request.getContent()).status(PostStatus.PUBLISHED).build();
        com.mannschaft.app.timeline.dto.TimelineContentFingerprint fingerprint=null;
        try {
            fingerprint=fingerprints.fingerprint(actor,at,post.getContent(),List.of());
            post.freezeRanchOriginalMetadata(at,actor);
        } catch(RuntimeException ignored) { telemetry.lost(TimelineRanchCaptureTelemetry.Reason.CAPTURE_FAILED); }
        post=posts.saveAndFlush(post);
        TimelineRanchCapture capture=null;
        if(fingerprint!=null) {
            try {
                var payload=new TimelineRanchRewardPayload(UuidV7.generate(),1,RanchRewardSourceType.TIMELINE_ORIGINAL,
                        RanchRewardEnvelope.IdType.LONG,post.getId().toString(),RanchRewardEnvelope.ScopeType.PERSONAL,null,null,
                        RanchRewardEnvelope.ActorKind.USER,actor,null,actor,actor,at,RanchRewardEnvelope.Origin.ORIGINAL,
                        new RanchRewardEnvelope.Timeline(RanchRewardEnvelope.PostOrigin.ORIGINAL,true));
                capture=new TimelineRanchCapture(payload,fingerprint);
            } catch(RuntimeException ignored) { telemetry.lost(TimelineRanchCaptureTelemetry.Reason.CAPTURE_FAILED); }
        }
        events.publish(new com.mannschaft.app.timeline.event.TimelinePostCreatedEvent(post.getId(),actor,scope.name(),post.getScopeId()));
        return new Outcome(mapper.toPostResponse(post),capture);
    }
}
