package com.mannschaft.app.reflection.service;

import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.reflection.dto.ReflectionRecallRewardPayload;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 元業務threadでDB処理へfallbackしない。明示容量が無い環境は受付せず本体を優先する。 */
@Slf4j
@Component
public class ReflectionRecallRewardQueue implements DisposableBean {
    private final UserRewardDeliveryGuard guard;
    private final ReflectionRanchTransportWriter writer;
    private final ThreadPoolExecutor executor;
    private final Counter disabled;
    private final Counter rejected;
    private final Counter failed;

    public ReflectionRecallRewardQueue(UserRewardDeliveryGuard guard,ReflectionRanchTransportWriter writer,
            MeterRegistry metrics,
            @Value("${ranch.source.transport.queue-capacity:0}") int capacity) {
        this.guard=guard;
        this.writer=writer;
        disabled=metrics.counter("ranch.source.capture.lost","source","PERSONAL_RECALL_COMPLETE","classification","QUEUE_DISABLED");
        rejected=metrics.counter("ranch.source.capture.lost","source","PERSONAL_RECALL_COMPLETE","classification","QUEUE_REJECTED");
        failed=metrics.counter("ranch.source.capture.lost","source","PERSONAL_RECALL_COMPLETE","classification","TRANSPORT_FAILED");
        if(capacity<0 || capacity>1000) throw new IllegalArgumentException("配送キュー容量が範囲外です");
        executor=capacity==0?null:new ThreadPoolExecutor(1,2,30,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),task->{
                    Thread thread=new Thread(task,"reflection-ranch-capture");
                    thread.setDaemon(true);
                    return thread;
                },new ThreadPoolExecutor.AbortPolicy());
    }

    /** auth proxyの正常復帰後にのみ呼ぶ。同期DB処理やCallerRunsを行わない。 */
    public void offer(ReflectionRecallRewardPayload fact) {
        if(executor==null) { disabled.increment(); return; }
        try { executor.execute(()->accept(fact)); }
        catch(RuntimeException rejection) { rejected.increment(); log.warn("想起配送受付を喪失: classification=QUEUE_REJECTED"); }
    }

    private void accept(ReflectionRecallRewardPayload fact) {
        try {
            guard.withLockedDeliveryUser(fact.recipientUserId(),state->switch(state.lifecycle()) {
                case PURGING,PURGED,ABSENT -> false;
                // ACTIVE保護下の過去factを保存できる。現在非ACTIVEならconsumer側でDEFERする。
                case ACTIVE,FROZEN,WITHDRAWAL,INELIGIBLE -> writer.accept(fact);
            });
        } catch(RuntimeException failure) {
            failed.increment();
            log.warn("想起配送受付を喪失: classification=TRANSPORT_FAILED exceptionClass={}",failure.getClass().getSimpleName());
        }
    }

    @Override public void destroy() { if(executor!=null) executor.shutdownNow(); }
}
