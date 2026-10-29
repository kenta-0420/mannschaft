package com.mannschaft.app.moduleevent;

import org.springframework.beans.factory.config.BeanPostProcessor;

/** Orderedな標準Async処理後、M方法だけへ外側captureを追加する。まだ自動登録しない。 */
public final class ModuleEventInvocationCapturePostProcessor implements BeanPostProcessor {
    private final ModuleEventExecutionFrame.Context context;

    public ModuleEventInvocationCapturePostProcessor(ModuleEventExecutionFrame.Context context) {
        this.context = context;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
