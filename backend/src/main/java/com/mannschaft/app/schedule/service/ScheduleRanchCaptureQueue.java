package com.mannschaft.app.schedule.service;

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
class ScheduleRanchCaptureQueue {
    private final UserRewardDeliveryGuard users;
    private final ScheduleRanchTransportWriter writer;
    private final ScheduleRanchCaptureTelemetry telemetry;
    private final ThreadPoolExecutor executor;
    ScheduleRanchCaptureQueue(UserRewardDeliveryGuard users,ScheduleRanchTransportWriter writer,
            ScheduleRanchCaptureTelemetry telemetry,@Value("${ranch.source.attendance.queue-capacity:0}") int capacity) {
        this.users=users;this.writer=writer;this.telemetry=telemetry;
        if(capacity<0 || capacity>1000) {
            telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.QUEUE_CONFIGURATION_INVALID);executor=null;
        } else if(capacity==0) executor=null;
        else executor=new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),
                operation -> { var thread=new Thread(operation,"attendance-ranch-capture");thread.setDaemon(true);return thread; },
                new ThreadPoolExecutor.AbortPolicy());
    }
    void offer(ScheduleRanchCapture capture) {
        if(capture==null) return;
        if(executor==null) { telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.QUEUE_DISABLED);return; }
        try { executor.execute(() -> receive(capture)); }
        catch(RejectedExecutionException ignored) { telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.QUEUE_REJECTED); }
        catch(RuntimeException ignored) { telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.QUEUE_REJECTED); }
    }
    private void receive(ScheduleRanchCapture capture) {
        try {
            users.withLockedDeliveryUser(capture.payload().recipientUserId(),state -> switch(state.lifecycle()) {
                case PURGING,PURGED,ABSENT -> false;
                case ACTIVE,FROZEN,WITHDRAWAL,INELIGIBLE -> writer.accept(capture);
            });
        } catch(RuntimeException ignored) { telemetry.lost(ScheduleRanchCaptureTelemetry.Reason.TRANSPORT_FAILED); }
    }
    @PreDestroy void close() { if(executor!=null) executor.shutdown(); }
}
