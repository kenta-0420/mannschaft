package com.mannschaft.app.ranch.reward;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 明示的に有効化し、有限の間隔とロック期間を登録した時だけ配送を起動する。 */
class RanchRewardDeliverySchedulerTest {
    private final RanchRewardDeliveryOrchestrator orchestrator = mock(RanchRewardDeliveryOrchestrator.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RanchRewardDeliveryScheduler.class)
            .withBean(RanchRewardDeliveryOrchestrator.class, () -> orchestrator);

    @Test
    void absentOrFalseActivationDoesNotRegisterWorker() {
        runner.run(context -> assertThat(context).doesNotHaveBean(RanchRewardDeliveryScheduler.class));
        runner.withPropertyValues("mannschaft.ranch.delivery.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RanchRewardDeliveryScheduler.class));
    }

    @Test
    void enabledWorkerRequiresExplicitPositiveIntervalAndLockDuration() {
        runner.withPropertyValues("mannschaft.ranch.delivery.worker.enabled=true")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        runner.withPropertyValues("mannschaft.ranch.delivery.worker.enabled=true",
                        "mannschaft.ranch.delivery.worker.interval-ms=0",
                        "mannschaft.ranch.delivery.worker.lock-at-most=PT30S")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        runner.withPropertyValues("mannschaft.ranch.delivery.worker.enabled=true",
                        "mannschaft.ranch.delivery.worker.interval-ms=1000",
                        "mannschaft.ranch.delivery.worker.lock-at-most=PT0S")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        runner.withPropertyValues("mannschaft.ranch.delivery.worker.enabled=true",
                        "mannschaft.ranch.delivery.worker.interval-ms=1000",
                        "mannschaft.ranch.delivery.worker.lock-at-most=PT1S")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void enabledWorkerPollsOneBoundedOrchestration() {
        when(orchestrator.drainOnce()).thenReturn(RanchRewardDeliveryOrchestrator.RunSummary.EMPTY);
        runner.withPropertyValues("mannschaft.ranch.delivery.worker.enabled=true",
                        "mannschaft.ranch.delivery.worker.interval-ms=1000",
                        "mannschaft.ranch.delivery.worker.lock-at-most=PT30S")
                .run(context -> {
                    assertThat(context).hasSingleBean(RanchRewardDeliveryScheduler.class);
                    context.getBean(RanchRewardDeliveryScheduler.class).poll();
                    verify(orchestrator).drainOnce();
                });
    }

    @Test
    void annotationDefaultsDoNotReplaceEitherRequiredRuntimeTimingSetting() {
        runner.withPropertyValues("mannschaft.ranch.delivery.worker.enabled=true",
                        "mannschaft.ranch.delivery.worker.interval-ms=1000")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        runner.withPropertyValues("mannschaft.ranch.delivery.worker.enabled=true",
                        "mannschaft.ranch.delivery.worker.lock-at-most=PT30S")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }
}
