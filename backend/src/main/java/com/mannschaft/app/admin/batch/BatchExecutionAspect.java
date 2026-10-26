package com.mannschaft.app.admin.batch;

import com.mannschaft.app.admin.batch.event.BatchCompletedEvent;
import com.mannschaft.app.admin.batch.event.BatchFailedEvent;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;

/**
 * F10.X 第一陣 — {@link BatchEndpoint} 付きメソッドを包む実行 Aspect。
 *
 * <p>動作:</p>
 * <ol>
 *   <li>{@link BatchJobLogService#startJob(String)} でログレコードを作成（status=RUNNING）。</li>
 *   <li>ターゲットメソッドを実行。</li>
 *   <li>正常終了時は {@link BatchJobLogService#completeJob(BatchJobLogEntity, int)} を呼び、
 *       戻り値が {@code int}/{@code long} ならその値を {@code processedCount} に採用する
 *       （それ以外の戻り値型では 0 を使う）。続いて {@link BatchCompletedEvent} を発火する。</li>
 *   <li>例外時は {@link BatchJobLogService#failJob(BatchJobLogEntity, String)} を呼び、
 *       {@link BatchFailedEvent} を発火してから例外を再投げする。</li>
 * </ol>
 *
 * <p>既存 75 バッチには {@link BatchEndpoint} を付与しないため、本 Aspect は何にも影響しない。
 * 第一陣の責務は基盤の据え付けのみで、既存挙動は完全に不変である。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class BatchExecutionAspect {

    private final BatchJobLogService batchJobLogService;
    private final ApplicationEventPublisher eventPublisher;

    @Around("@annotation(com.mannschaft.app.admin.batch.BatchEndpoint)")
    public Object aroundBatchEndpoint(ProceedingJoinPoint pjp) throws Throwable {
        BatchEndpoint annotation = resolveAnnotation(pjp);
        String name = annotation.name();

        BatchJobLogEntity logEntity = batchJobLogService.startJob(name);
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
            try {
                batchJobLogService.failJob(logEntity, truncateMessage(message));
            } catch (Exception logEx) {
                // ログ書き込み失敗は元の例外を埋もれさせない
                log.warn("failJob 書き込み失敗: name={}, error={}", name, logEx.toString());
            }
            try {
                eventPublisher.publishEvent(new BatchFailedEvent(name, logEntity, ex, Instant.now()));
            } catch (Exception pubEx) {
                log.warn("BatchFailedEvent 発火失敗: name={}, error={}", name, pubEx.toString());
            }
            throw ex;
        }

        int processedCount = extractProcessedCount(result);
        try {
            batchJobLogService.completeJob(logEntity, processedCount);
        } catch (Exception logEx) {
            log.warn("completeJob 書き込み失敗: name={}, error={}", name, logEx.toString());
        }
        try {
            eventPublisher.publishEvent(new BatchCompletedEvent(name, logEntity, Instant.now()));
        } catch (Exception pubEx) {
            log.warn("BatchCompletedEvent 発火失敗: name={}, error={}", name, pubEx.toString());
        }
        return result;
    }

    private static BatchEndpoint resolveAnnotation(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        BatchEndpoint annotation = AnnotatedElementUtils.findMergedAnnotation(method, BatchEndpoint.class);
        if (annotation == null) {
            throw new IllegalStateException("@BatchEndpoint が見つかりません: method=" + method);
        }
        return annotation;
    }

    private static int extractProcessedCount(Object result) {
        if (result instanceof Integer i) {
            return i;
        }
        if (result instanceof Long l) {
            if (l > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            if (l < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            return l.intValue();
        }
        return 0;
    }

    /**
     * batch_job_logs.error_message は TEXT 列だが、過大な例外メッセージで I/O を膨らませないため、
     * ある程度の長さで切る。F12.5 側にはフルスタックトレースを別途送るので情報は失われない。
     */
    private static String truncateMessage(String message) {
        if (message == null) return null;
        int max = 2000;
        if (message.length() > max) {
            return message.substring(0, max);
        }
        return message;
    }
}
