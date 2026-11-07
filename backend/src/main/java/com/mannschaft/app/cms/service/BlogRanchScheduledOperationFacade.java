package com.mannschaft.app.cms.service;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 予約公開の既一件TXを一回だけ実行し、報酬障害で公開済み結果を失わせない。 */
@Service
@RequiredArgsConstructor
class BlogRanchScheduledOperationFacade {
    private final UserRewardDeliveryGuard users;
    private final BlogRanchAuthorReader authors;
    private final BlogScheduledPublishService publisher;
    private final BlogRanchCaptureQueue queue;
    private final BlogRanchCaptureTelemetry telemetry;
    Optional<Boolean> publish(Long postId,LocalDateTime baseTime) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) return Optional.empty();
        Long author;
        try { author=authors.author(postId).orElse(null); }
        catch(RuntimeException ignored) { telemetry.lost(BlogRanchCaptureTelemetry.Reason.UNSUPPORTED_CAPTURE);return Optional.empty(); }
        if(author==null) return Optional.empty();
        var started=new AtomicBoolean();
        var committed=new AtomicReference<Boolean>();
        var capture=new AtomicReference<BlogRanchCapture>();
        Boolean result;
        try {
            result=users.withLockedDeliveryUser(author,state -> {
                var context=new BlogRanchCaptureContext(author,state.lifecycle()==DeliveryUserState.Lifecycle.ACTIVE);
                started.set(true);
                boolean saved=publisher.publishScheduledPost(postId,baseTime,context);
                committed.set(saved);
                capture.set(context.take());
                return saved;
            });
        } catch(RuntimeException failure) {
            if(committed.get()!=null) {
                telemetry.lost(BlogRanchCaptureTelemetry.Reason.POST_NATIVE_FAILURE);
                return Optional.of(committed.get());
            }
            if(started.get()) throw failure;
            telemetry.lost(BlogRanchCaptureTelemetry.Reason.UNSUPPORTED_CAPTURE);
            return Optional.empty();
        }
        // 源commitとauth正常復帰を確認した後にのみ有限offerする。
        queue.offer(capture.get());
        return Optional.of(result);
    }
}
