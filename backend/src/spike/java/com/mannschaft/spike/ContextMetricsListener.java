package com.mannschaft.spike;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.cache.ContextCache;
import org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate;

/** 起動失敗も含む試験クラス経過時間と同一JVMの標準context cache統計だけを記録する。 */
public class ContextMetricsListener implements TestExecutionListener {
    private long started;
    private final CacheAccess access = new CacheAccess();

    @Override
    public int getOrder() { return Integer.MIN_VALUE; }

    @Override
    public void beforeTestClass(TestContext context) {
        started = System.nanoTime();
        record(context, "beforeClass");
    }

    @Override
    public void beforeTestMethod(TestContext context) { record(context, "beforeMethod"); }

    @Override
    public void afterTestClass(TestContext context) { record(context, "afterClass"); }

    private void record(TestContext context, String phase) {
        System.out.println("contextProbe class=" + context.getTestClass().getSimpleName()
                + " phase=" + phase + " elapsedMillis=" + (System.nanoTime() - started) / 1_000_000
                + " cache=" + access.shared());
    }

    /** Spring標準delegateと同じstatic cacheの読み取り専用窓口。 */
    private static class CacheAccess extends DefaultCacheAwareContextLoaderDelegate {
        ContextCache shared() { return getContextCache(); }
    }
}
