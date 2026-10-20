package com.mannschaft.app.reflection.service;

import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 元業務threadでDB処理へfallbackしない。明示容量が無い環境は受付せず本体を優先する。 */
@Component
public class ReflectionRecallRewardQueue implements DisposableBean {
    private final UserRewardDeliveryGuard guard;
    private final ReflectionRanchTransportWriter writer;
    private final ThreadPoolExecutor executor;
    private final ReflectionRanchCaptureTelemetry telemetry;

    public ReflectionRecallRewardQueue(UserRewardDeliveryGuard guard,ReflectionRanchTransportWriter writer,
            ReflectionRanchCaptureTelemetry telemetry,
            @Value("${ranch.source.transport.queue-capacity:0}") int capacity) {
        this.guard=guard;
        this.writer=writer;
        this.telemetry=telemetry;
        if(capacity<0 || capacity>1000) {
            telemetry.lost(ReflectionRanchCaptureTelemetry.Reason.QUEUE_CONFIGURATION_INVALID,null);
            executor=null;
            return;
        }
        executor=capacity==0?null:new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),task->{
                    Thread thread=new Thread(task,"reflection-ranch-capture");
                    thread.setDaemon(true);
                    return thread;
                },new ThreadPoolExecutor.AbortPolicy());
    }

    /** auth proxyの正常復帰後にのみ呼ぶ。同期DB処理やCallerRunsを行わない。 */
    public void offer(ReflectionRecallRewardPayload fact) {
        if(executor==null) { telemetry.lost(ReflectionRanchCaptureTelemetry.Reason.QUEUE_DISABLED,null); return; }
        try { executor.execute(()->accept(fact)); }
        catch(RuntimeException rejection) { telemetry.lost(ReflectionRanchCaptureTelemetry.Reason.QUEUE_REJECTED,rejection.getClass()); }
    }

    private void accept(ReflectionRecallRewardPayload fact) {
        try {
            guard.withLockedDeliveryUser(fact.recipientUserId(),state->switch(state.lifecycle()) {
                case PURGING,PURGED,ABSENT -> false;
                // ACTIVE保護下の過去factを保存できる。現在非ACTIVEならconsumer側でDEFERする。
                case ACTIVE,FROZEN,WITHDRAWAL,INELIGIBLE -> writer.accept(fact);
            });
        } catch(RuntimeException failure) {
            telemetry.lost(ReflectionRanchCaptureTelemetry.Reason.TRANSPORT_FAILED,failure.getClass());
        }
    }

    @Override public void destroy() { if(executor!=null) executor.shutdownNow(); }
}
