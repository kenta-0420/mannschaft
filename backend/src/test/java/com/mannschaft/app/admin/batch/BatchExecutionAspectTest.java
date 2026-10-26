package com.mannschaft.app.admin.batch;

import com.mannschaft.app.admin.batch.event.BatchCompletedEvent;
import com.mannschaft.app.admin.batch.event.BatchFailedEvent;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BatchExecutionAspect} の単体テスト。
 *
 * <p>正常終了 / 例外 / 戻り値が int の場合の processedCount 抽出について検証する。</p>
 */
@DisplayName("BatchExecutionAspect 単体テスト")
class BatchExecutionAspectTest {

    private BatchJobLogService batchJobLogService;
    private ApplicationEventPublisher eventPublisher;
    private BatchExecutionAspect aspect;

    @BeforeEach
    void setUp() {
        batchJobLogService = mock(BatchJobLogService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        aspect = new BatchExecutionAspect(batchJobLogService, eventPublisher);
    }

    @Test
    @DisplayName("正常終了時に completeJob と BatchCompletedEvent が発火する")
    void shouldCompleteJobAndPublishEventOnSuccess() throws Throwable {
        BatchJobLogEntity logEntity = BatchJobLogEntity.builder().build();
        given(batchJobLogService.startJob("sample-foo")).willReturn(logEntity);

        ProceedingJoinPoint pjp = mockJoinPoint("sample-foo", "foo");
        given(pjp.proceed()).willReturn(null);

        Object result = aspect.aroundBatchEndpoint(pjp);

        assertThat(result).isNull();
        verify(batchJobLogService).completeJob(eq(logEntity), eq(0));
        verify(batchJobLogService, never()).failJob(any(), any());

        ArgumentCaptor<BatchCompletedEvent> evtCaptor = ArgumentCaptor.forClass(BatchCompletedEvent.class);
        verify(eventPublisher).publishEvent(evtCaptor.capture());
        assertThat(evtCaptor.getValue().name()).isEqualTo("sample-foo");
        assertThat(evtCaptor.getValue().log()).isSameAs(logEntity);
    }

    @Test
    @DisplayName("int 戻り値が processedCount に採用される")
    void shouldUseIntReturnValueAsProcessedCount() throws Throwable {
        BatchJobLogEntity logEntity = BatchJobLogEntity.builder().build();
        given(batchJobLogService.startJob("sample-foo")).willReturn(logEntity);

        ProceedingJoinPoint pjp = mockJoinPoint("sample-foo", "foo");
        given(pjp.proceed()).willReturn(42);

        aspect.aroundBatchEndpoint(pjp);

        verify(batchJobLogService).completeJob(eq(logEntity), eq(42));
    }

    @Test
    @DisplayName("long 戻り値が int にクランプされて processedCount に採用される")
    void shouldClampLongReturnValue() throws Throwable {
        BatchJobLogEntity logEntity = BatchJobLogEntity.builder().build();
        given(batchJobLogService.startJob("sample-foo")).willReturn(logEntity);

        ProceedingJoinPoint pjp = mockJoinPoint("sample-foo", "foo");
        given(pjp.proceed()).willReturn(123L);

        aspect.aroundBatchEndpoint(pjp);

        verify(batchJobLogService).completeJob(eq(logEntity), eq(123));
    }

    @Test
    @DisplayName("例外発生時に failJob と BatchFailedEvent が発火し、例外がそのまま投げ直される")
    void shouldFailJobAndPublishEventOnException() throws Throwable {
        BatchJobLogEntity logEntity = BatchJobLogEntity.builder().build();
        given(batchJobLogService.startJob("sample-foo")).willReturn(logEntity);

        RuntimeException boom = new RuntimeException("kaboom");
        ProceedingJoinPoint pjp = mockJoinPoint("sample-foo", "foo");
        given(pjp.proceed()).willThrow(boom);

        assertThatThrownBy(() -> aspect.aroundBatchEndpoint(pjp))
                .isSameAs(boom);

        verify(batchJobLogService).failJob(eq(logEntity), eq("kaboom"));
        verify(batchJobLogService, never()).completeJob(any(), any(Integer.class));

        ArgumentCaptor<BatchFailedEvent> evtCaptor = ArgumentCaptor.forClass(BatchFailedEvent.class);
        verify(eventPublisher).publishEvent(evtCaptor.capture());
        assertThat(evtCaptor.getValue().name()).isEqualTo("sample-foo");
        assertThat(evtCaptor.getValue().cause()).isSameAs(boom);
        assertThat(evtCaptor.getValue().log()).isSameAs(logEntity);
    }

    /** ProceedingJoinPoint のモックを組み立てる。 */
    private static ProceedingJoinPoint mockJoinPoint(String batchName, String methodName) throws NoSuchMethodException {
        Method method = pickFixtureMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        given(signature.getMethod()).willReturn(method);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        given(pjp.getSignature()).willReturn(signature);
        // batchName は @BatchEndpoint のリフレクション読み取りで決まるため、Fixture 側を name で振り分ける
        // ここでは Fixture#foo/bar の name 属性が batchName 引数と一致する設計とする
        if (!batchName.equals(method.getAnnotation(BatchEndpoint.class).name())) {
            throw new IllegalStateException("Fixture メソッド " + methodName + " の name が一致しません");
        }
        return pjp;
    }

    private static Method pickFixtureMethod(String name) throws NoSuchMethodException {
        return AspectFixture.class.getMethod(name);
    }

    /** リフレクションで @BatchEndpoint を抽出するためのテスト Fixture。 */
    @SuppressWarnings("unused")
    static class AspectFixture {
        @BatchEndpoint(name = "sample-foo", description = "foo desc")
        public void foo() {
        }

        @BatchEndpoint(name = "sample-bar")
        public void bar() {
        }
    }
}
