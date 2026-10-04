package com.mannschaft.app.cms.service;

import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 既存CMS本体を一回だけ実行し、追加auth境界の失敗で成功ACKを失わせない。 */
@Service
@RequiredArgsConstructor
public class BlogRanchNativeOperationFacade {
    private final UserOperationGuard users;
    private final BlogRanchAuthorReader authors;
    private final BlogRanchNativeWriter writer;
    private final BlogRanchCaptureQueue queue;
    private final BlogRanchCaptureTelemetry telemetry;

    public Optional<BlogPostResponse> changeStatus(Long id,Long actor,PublishRequest request,boolean impersonated) {
        return execute(id,actor,impersonated,() -> writer.changeStatus(id,actor,request));
    }
    public Optional<BlogPostResponse> selfReview(Long id,Long actor,SelfReviewRequest request,boolean impersonated) {
        return execute(id,actor,impersonated,() -> writer.selfReview(id,actor,request));
    }
    private Optional<BlogPostResponse> execute(Long id,Long actor,boolean impersonated,
            Supplier<BlogRanchNativeWriter.Outcome> nativeOperation) {
        if(impersonated || actor==null || TransactionSynchronizationManager.isActualTransactionActive()) return Optional.empty();
        try { if(!authors.isAuthor(id,actor)) return Optional.empty(); }
        catch(RuntimeException ignored) { telemetry.lost(BlogRanchCaptureTelemetry.Reason.UNSUPPORTED_CAPTURE);return Optional.empty(); }
        var started=new AtomicBoolean();
        var committed=new AtomicReference<BlogRanchNativeWriter.Outcome>();
        BlogRanchNativeWriter.Outcome outcome;
        try {
            outcome=users.withActiveUser(actor,() -> {
                started.set(true);
                var saved=nativeOperation.get();
                // native proxy正常復帰時点で本体commit済み。外authのcommit失敗と区別する。
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
        // auth proxyの正常commit復帰より前にofferしない。
        queue.offer(outcome.capture());
        return Optional.of(outcome.response());
    }
}
