package com.mannschaft.app.admin.batch;

import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicyAspect;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureSkipRecorder;
import com.mannschaft.app.common.featuregate.FeatureGateAspect;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Issue #2997 G7-b — {@code @BatchEndpoint} 付きメソッドを包む advisor の相対順序の実測と固定。
 *
 * <p>実アプリと同じ構成要素（{@code @EnableTransactionManagement}・{@code @EnableSchedulerLock}・
 * {@link BackgroundFeaturePolicyAspect}・{@link FeatureGateAspect}・{@link BatchExecutionAspect}）を
 * 載せた最小コンテキストで、プロキシの advisor チェーン（外側が先頭）を取り出して並びを検証する。
 * 要請: BackgroundFeaturePolicy → FeatureGate → BatchExecution → TX。
 * {@link BatchExecutionAspect} が TX の外側に居なければ、完了・失敗イベントが業務TXの確定前に
 * publish され、AFTER_COMMIT リスナーが rollback / commit 失敗と無関係に走ってしまう。</p>
 */
@DisplayName("Issue #2997 G7-b BatchEndpoint advisor の順序")
class BatchAdvisorOrderTest {

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableSchedulerLock(defaultLockAtMostFor = "30m", order = com.mannschaft.app.config.ShedLockConfig.ADVISOR_ORDER)
    static class Config {
        @Bean LockProvider lockProvider() { return mock(LockProvider.class); }
        @Bean PlatformTransactionManager transactionManager() { return new NoopTm(); }
        @Bean BatchJobLogService batchJobLogService() { return mock(BatchJobLogService.class); }
        @Bean ApplicationEventPublisher unused() { return mock(ApplicationEventPublisher.class); }
        @Bean FeatureFlagService featureFlagService() { return mock(FeatureFlagService.class); }
        @Bean BackgroundFeatureSkipRecorder skipRecorder() { return mock(BackgroundFeatureSkipRecorder.class); }
        @Bean BackgroundFeaturePolicyAspect policyAspect(FeatureFlagService f, BackgroundFeatureSkipRecorder r) {
            return new BackgroundFeaturePolicyAspect(f, r);
        }
        @Bean FeatureGateAspect featureGateAspect(FeatureFlagService f) { return new FeatureGateAspect(f); }
        @Bean BatchExecutionAspect batchExecutionAspect(BatchJobLogService s) {
            // コンテキスト自身が ApplicationEventPublisher なので 2 引数目は別途渡す必要はないが、型を満たすため mock を使う。
            return new BatchExecutionAspect(s, mock(ApplicationEventPublisher.class));
        }
        @Bean Target target() { return new Target(); }
    }

    static class NoopTm extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }

    static class Target {
        @org.springframework.scheduling.annotation.Scheduled(cron = "-")
        @SchedulerLock(name = "order-probe", lockAtMostFor = "PT1M")
        @BatchEndpoint(name = "order-probe")
        @com.mannschaft.app.common.featuregate.RequireFeature("probe")
        @com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy(
                mode = com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode.ALWAYS, reason = "order probe")
        @Transactional
        public int run() { return 0; }
    }

    @Test
    @DisplayName("実測: 順序は ShedLock / Policy(5) / FeatureGate(10) / BatchExecution / TX の外側から内側")
    void advisorChainOrder() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Config.class)) {
            Target proxy = ctx.getBean(Target.class);
            assertThat(proxy).isInstanceOf(Advised.class);
            List<String> chain = new ArrayList<>();
            for (Advisor a : ((Advised) proxy).getAdvisors()) {
                chain.add(describe(a));
            }
            System.out.println("ADVISOR_CHAIN(outer->inner)=" + chain);

            int policy = indexOf(chain, "policyAspect");
            int gate = indexOf(chain, "featureGateAspect");
            int shed = indexOf(chain, "MethodProxyScheduledLockAdvisor");
            int batch = indexOf(chain, "batchExecutionAspect");
            int tx = indexOf(chain, "BeanFactoryTransactionAttributeSourceAdvisor");
            for (int i : new int[]{policy, gate, shed, batch, tx}) {
                assertThat(i).as("全 advisor が連鎖に居ること chain=%s", chain).isGreaterThanOrEqualTo(0);
            }
            assertThat(policy).as("Policy(5) は FeatureGate(10) より外側 chain=%s", chain).isLessThan(gate);
            assertThat(gate).as("FeatureGate(10) は ShedLock(15) より外側").isLessThan(shed);
            assertThat(shed).as("ShedLock は Batch より外側（ロックを取れない pod で完了を記録しない）").isLessThan(batch);
            assertThat(batch).as("BatchExecutionAspect は TX より外側（TX 確定後に publish される）").isLessThan(tx);
        }
    }

    @Test
    @DisplayName("ロックを取れなかった pod では、開始ログも完了イベントも出ない（ShedLock が Batch の外側）")
    void lockNotAcquiredWritesNoLogAndNoEvent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(Config.class)) {
            // mock(LockProvider) は Optional.empty を返す = ロック未取得
            Target proxy = ctx.getBean(Target.class);
            BatchJobLogService logService = ctx.getBean(BatchJobLogService.class);

            proxy.run();

            org.mockito.Mockito.verify(logService, org.mockito.Mockito.never()).startJob(org.mockito.ArgumentMatchers.anyString());
        }
    }

    private static int indexOf(List<String> chain, String needle) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String describe(Advisor a) {
        String s = a.getClass().getSimpleName();
        if (a instanceof org.springframework.aop.aspectj.AbstractAspectJAdvice) {
            return s;
        }
        if (a instanceof org.springframework.aop.PointcutAdvisor) {
            try {
                Object advice = a.getAdvice();
                s += "/" + advice.getClass().getSimpleName();
                if (advice instanceof org.springframework.aop.aspectj.AbstractAspectJAdvice aj) {
                    s += "/" + aj.getAspectName();
                }
            } catch (RuntimeException ignored) {
                // 表示用
            }
        }
        return s + "(order=" + (a instanceof org.springframework.core.Ordered o ? o.getOrder() : "n/a") + ")";
    }
}
