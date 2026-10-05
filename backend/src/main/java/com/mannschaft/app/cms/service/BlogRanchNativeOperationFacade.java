package com.mannschaft.app.cms.service;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 公開者とauthorを分け、author非ACTIVEでも元CMS認可が通る本体保存は維持する。 */
@Service
@RequiredArgsConstructor
public class BlogRanchNativeOperationFacade {
    private final UserRewardDeliveryGuard users;
    private final BlogRanchAuthorReader authors;
    private final BlogRanchNativeWriter writer;
    private final BlogRanchCaptureQueue queue;
    private final BlogRanchCaptureTelemetry telemetry;
    public Optional<BlogPostResponse> changeStatus(Long id,Long actor,PublishRequest request,boolean impersonated) {
        return execute(id,actor,impersonated,(author,qualified) -> writer.changeStatus(id,actor,request,author,qualified));
    }
    public Optional<BlogPostResponse> selfReview(Long id,Long actor,SelfReviewRequest request,boolean impersonated) {
        return execute(id,actor,impersonated,(author,qualified) -> writer.selfReview(id,actor,request,author,qualified));
    }
    private Optional<BlogPostResponse> execute(Long id,Long actor,boolean impersonated,
            BiFunction<Long,Boolean,BlogRanchNativeWriter.Outcome> nativeOperation) {
        if(impersonated || actor==null || TransactionSynchronizationManager.isActualTransactionActive()) return Optional.empty();
        Long author;
        try { author=authors.author(id).orElse(null); }
        catch(RuntimeException ignored) { telemetry.lost(BlogRanchCaptureTelemetry.Reason.UNSUPPORTED_CAPTURE);return Optional.empty(); }
        if(author==null) return Optional.empty();
        var started=new AtomicBoolean();
        var committed=new AtomicReference<BlogRanchNativeWriter.Outcome>();
        BlogRanchNativeWriter.Outcome outcome;
        try {
            outcome=users.withLockedDeliveryUsers(java.util.List.of(actor,author),states -> {
                started.set(true);
                var saved=nativeOperation.apply(author,states.get(actor).lifecycle()==DeliveryUserState.Lifecycle.ACTIVE && states.get(author).lifecycle()==DeliveryUserState.Lifecycle.ACTIVE);
                committed.set(saved);
                return saved;
            });
        } catch(RuntimeException failure) {
            if(committed.get()!=null) {
                telemetry.lost(BlogRanchCaptureTelemetry.Reason.POST_NATIVE_FAILURE);
                return Optional.of(committed.get().response());
            }
            if(started.get()) throw failure;
            telemetry.lost(BlogRanchCaptureTelemetry.Reason.UNSUPPORTED_CAPTURE);
            return Optional.empty();
        }
        // native commitとauth正常復帰の後だけ、有限offerを行う。
        queue.offer(outcome.capture());
        return Optional.of(outcome.response());
    }
}
