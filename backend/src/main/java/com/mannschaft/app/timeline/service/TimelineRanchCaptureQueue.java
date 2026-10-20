package com.mannschaft.app.timeline.service;

import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本体・auth正常commit後だけ有限キューへ受付する。呼出元でDB配送を代行しない。 */
@Component
class TimelineRanchCaptureQueue {
    private final UserRewardDeliveryGuard users;
    private final TimelineRanchTransportWriter writer;
    private final TimelineRanchCaptureTelemetry telemetry;
    private final ThreadPoolExecutor executor;
    TimelineRanchCaptureQueue(UserRewardDeliveryGuard users,TimelineRanchTransportWriter writer,
            TimelineRanchCaptureTelemetry telemetry,@Value("${ranch.source.timeline.queue-capacity:0}") int capacity) {
        this.users=users;this.writer=writer;this.telemetry=telemetry;
        if(capacity<0 || capacity>1000) {
            telemetry.lost(TimelineRanchCaptureTelemetry.Reason.QUEUE_CONFIGURATION_INVALID);executor=null;
        } else if(capacity==0) executor=null;
        else executor=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),
                operation -> { var thread=new Thread(operation,"timeline-ranch-capture");thread.setDaemon(true);return thread; },
                new ThreadPoolExecutor.AbortPolicy());
    }
    void offer(TimelineRanchCapture capture) {
        if(capture==null) return;
        if(executor==null) { telemetry.lost(TimelineRanchCaptureTelemetry.Reason.QUEUE_DISABLED);return; }
        try { executor.execute(() -> receive(capture)); }
        catch(RejectedExecutionException ignored) { telemetry.lost(TimelineRanchCaptureTelemetry.Reason.QUEUE_REJECTED); }
        catch(RuntimeException ignored) { telemetry.lost(TimelineRanchCaptureTelemetry.Reason.QUEUE_REJECTED); }
    }
    private void receive(TimelineRanchCapture capture) {
        try {
            users.withLockedDeliveryUser(capture.payload().recipientUserId(),state -> switch(state.lifecycle()) {
                case PURGING,PURGED,ABSENT -> false;
                case ACTIVE,FROZEN,WITHDRAWAL,INELIGIBLE -> writer.accept(capture);
            });
        } catch(RuntimeException ignored) { telemetry.lost(TimelineRanchCaptureTelemetry.Reason.TRANSPORT_FAILED); }
    }
    @PreDestroy void close() { if(executor!=null) executor.shutdown(); }
}
