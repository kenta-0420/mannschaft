package com.mannschaft.app.timeline.service;

import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.PostStatus;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 未接続経路は従来保存一回へ戻す。captureやauth後段の故障で本体成功ACKを失わせない。 */
@Service
@RequiredArgsConstructor
public class TimelineRanchNativeOperationFacade {
    private final UserOperationGuard users;
    private final TimelineRanchNativeWriter writer;
    private final TimelineRanchCaptureQueue queue;
    private final TimelineRanchCaptureTelemetry telemetry;
    public Optional<PostResponse> create(CreatePostRequest request,Long scopeId,Long actor,boolean impersonated) {
        if(!supports(request,actor,impersonated) || TransactionSynchronizationManager.isActualTransactionActive()) return Optional.empty();
        var started=new AtomicBoolean();var committed=new AtomicReference<TimelineRanchNativeWriter.Outcome>();
        TimelineRanchNativeWriter.Outcome outcome;
        try {
            outcome=users.withActiveUser(actor,() -> {
                started.set(true);var saved=writer.create(request,scopeId,actor);committed.set(saved);return saved;
            });
        } catch(RuntimeException failure) {
            if(committed.get()!=null) {
                telemetry.lost(TimelineRanchCaptureTelemetry.Reason.POST_NATIVE_FAILURE);
                return Optional.of(committed.get().response());
            }
            if(started.get()) throw failure;
            telemetry.lost(TimelineRanchCaptureTelemetry.Reason.UNSUPPORTED_CAPTURE);return Optional.empty();
        }
        queue.offer(outcome.capture());return Optional.of(outcome.response());
    }
    private boolean supports(CreatePostRequest request,Long actor,boolean impersonated) {
        if(request==null || actor==null || impersonated) return false;
        String scope=request.getScopeTypeOrDefault();
        return ("PUBLIC".equals(scope) || "PERSONAL".equals(scope)) && "USER".equals(request.getPostedAsTypeOrDefault())
                && (request.getPostedAsId()==null || actor.equals(request.getPostedAsId()))
                && request.getParentId()==null && request.getRepostOfId()==null && request.getScheduledAt()==null
                && (request.getStatus()==null || request.getStatus()==PostStatus.PUBLISHED)
                && request.getPoll()==null && (request.getAttachments()==null || request.getAttachments().isEmpty())
                && request.getContent()!=null && request.getContent().length()<=5000;
    }
}
