package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ModuleEventListener;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.interceptor.AsyncExecutionInterceptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** Orderedな標準Async処理後、M方法だけへ外側captureを追加する。まだ自動登録しない。 */
public final class ModuleEventInvocationCapturePostProcessor implements BeanPostProcessor {
    private final ModuleEventExecutionFrame.Context context;

    public ModuleEventInvocationCapturePostProcessor(ModuleEventExecutionFrame.Context context) {
        this.context = context;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        var methods = MethodIntrospector.selectMethods(targetClass,
                (MethodIntrospector.MetadataLookup<ModuleEventListener>) method -> listener(method, targetClass));
        if (methods.isEmpty()) return bean;
        if (!(bean instanceof Advised advised) || advised.isFrozen()) {
            throw new BeanCreationException(beanName, "Mリスナーには変更可能な標準Async proxyが必要です");
        }
        boolean hasAsync = false;
        for (var advisor : advised.getAdvisors()) {
            if (advisor.getAdvice() instanceof CaptureInterceptor) return bean;
            if (advisor.getAdvice() instanceof AsyncExecutionInterceptor) hasAsync = true;
        }
        if (!hasAsync) throw new BeanCreationException(beanName, "標準Async advisorの装着前です");
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> type) {
                return listener(method, targetClass) != null;
            }
        };
        // Non-Ordered BPPなので標準Ordered Async BPPの後。順位数値の同着に依存しない。
        advised.addAdvisor(0, new DefaultPointcutAdvisor(pointcut, new CaptureInterceptor(context, targetClass)));
        return bean;
    }

    private static ModuleEventListener listener(Method method, Class<?> type) {
        Method specific = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, type));
        ModuleEventListener annotation = AnnotatedElementUtils.findMergedAnnotation(specific, ModuleEventListener.class);
        return annotation != null ? annotation : AnnotatedElementUtils.findMergedAnnotation(method, ModuleEventListener.class);
    }

    private static final class CaptureInterceptor implements MethodInterceptor {
        private final ModuleEventExecutionFrame.Context context;
        private final Class<?> targetClass;
        private CaptureInterceptor(ModuleEventExecutionFrame.Context context, Class<?> targetClass) {
            this.context = context;
            this.targetClass = targetClass;
        }
        @Override public Object invoke(MethodInvocation invocation) throws Throwable {
            ModuleEventListener annotation = listener(invocation.getMethod(), targetClass);
            Object event = invocation.getArguments()[0];
            ModuleEventExecutionFrame frame = context.capture(event, annotation.id()).orElseThrow(() ->
                    new IllegalStateException("投入するMイベントの世代登録がありません"));
            try (var ignored = context.submitting(frame)) {
                return invocation.proceed();
            }
        }
    }
}
