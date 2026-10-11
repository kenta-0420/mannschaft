package com.mannschaft.app.notification.outbox;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.team.service.TeamNotificationOutboxSource;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知 outbox の tx 境界・起こし・ポーラー・バッチの契約（反射テスト。docs/architecture/notification_outbox.md）。
 *
 * <ul>
 *   <li>OB10（反射の部分）: 起こしは {@code @TransactionalEventListener(AFTER_COMMIT)} と executor 指定の {@code @Async}</li>
 *   <li>OB12: ポーラーと各バッチに一意の {@code @SchedulerLock} 名と {@code @BackgroundFeaturePolicy}</li>
 *   <li>OB16: 取り込みは relay と別 Bean で実効 {@code REQUIRES_NEW}、claim と印付けも {@code REQUIRES_NEW}、relay は tx を持たない</li>
 * </ul>
 */
@DisplayName("通知 outbox の tx 境界・起こし・ポーラー・バッチの契約")
class NotificationOutboxContractTest {

    // =====================================================================
    // OB16 tx 境界
    // =====================================================================

    @Test
    @DisplayName("OB16 取り込み ingest は relay と別の Bean で、実効 REQUIRES_NEW（CallerRuns で AFTER_COMMIT 内に同期実行されても独立した tx）")
    void ob16_取り込みは別BeanでREQUIRES_NEW() throws Exception {
        Method ingest = NotificationOutboxIngestService.class.getMethod("ingest", NotificationOutboxPayload.class);
        assertThat(effectivePropagation(ingest, NotificationOutboxIngestService.class))
                .isEqualTo(Propagation.REQUIRES_NEW);

        List<Class<?>> relayFieldTypes = Arrays.stream(NotificationOutboxRelay.class.getDeclaredFields())
                .map(Field::getType).collect(Collectors.toList());
        assertThat(relayFieldTypes).as("relay は取り込みを別 Bean として注入して呼ぶ（自己呼び出しで tx を素通りさせない）")
                .contains(NotificationOutboxIngestService.class);
        assertThat(NotificationOutboxIngestService.class).isNotEqualTo(NotificationOutboxRelay.class);
    }

    @Test
    @DisplayName("OB16 team の source の claim・印付け・回収・掃除はすべて実効 REQUIRES_NEW（MANDATORY・REQUIRED にしない）")
    void ob16_sourceの各操作はREQUIRES_NEW() throws Exception {
        List<Method> operations = List.of(
                TeamNotificationOutboxSource.class.getMethod("claim", int.class, Instant.class),
                TeamNotificationOutboxSource.class.getMethod("markRelayed", UUID.class, UUID.class, Instant.class),
                TeamNotificationOutboxSource.class.getMethod("markFailed", UUID.class, UUID.class, String.class,
                        Instant.class, boolean.class, Instant.class),
                TeamNotificationOutboxSource.class.getMethod("deferUnsupportedVersion", UUID.class, UUID.class,
                        Instant.class, Instant.class),
                TeamNotificationOutboxSource.class.getMethod("recoverStuck", Instant.class, Instant.class),
                TeamNotificationOutboxSource.class.getMethod("sweep", Instant.class, Instant.class));
        for (Method operation : operations) {
            assertThat(effectivePropagation(operation, TeamNotificationOutboxSource.class))
                    .as(operation.getName()).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    @Test
    @DisplayName("OB16 relay・回収バッチ・掃除バッチは tx を持たない（指揮役。tx は source と取り込みの側で切る）")
    void ob16_relayとバッチはtxを持たない() {
        for (Class<?> type : List.of(NotificationOutboxRelay.class, NotificationOutboxStuckRecoveryBatch.class,
                NotificationOutboxSweepBatch.class)) {
            assertThat(AnnotatedElementUtils.hasAnnotation(type, Transactional.class)).as(type.getSimpleName()).isFalse();
            for (Method method : type.getDeclaredMethods()) {
                assertThat(AnnotatedElementUtils.hasAnnotation(method, Transactional.class))
                        .as(type.getSimpleName() + "#" + method.getName()).isFalse();
            }
        }
    }

    // =====================================================================
    // OB10 起こし
    // =====================================================================

    @Test
    @DisplayName("OB10 起こしは AFTER_COMMIT の @TransactionalEventListener で、専用 executor notification-outbox-pool の @Async")
    void ob10_起こしはAFTER_COMMITと専用executorのAsync() throws Exception {
        Method onAppended = NotificationOutboxRelay.class.getMethod("onAppended", NotificationOutboxAppendedEvent.class);
        TransactionalEventListener listener = onAppended.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        Async async = onAppended.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("notification-outbox-pool");
    }

    // =====================================================================
    // OB12 ポーラー・バッチ
    // =====================================================================

    @Test
    @DisplayName("OB12 ポーラーは fixedDelay=5000・SchedulerLock(notificationOutboxRelay, PT1M, PT1S)・BackgroundFeaturePolicy(ALWAYS)")
    void ob12_ポーラーの注釈() throws Exception {
        Method poll = NotificationOutboxRelay.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(5000L);
        SchedulerLock lock = poll.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("notificationOutboxRelay");
        assertThat(Duration.parse(lock.lockAtMostFor())).isEqualTo(Duration.ofMinutes(1));
        assertThat(Duration.parse(lock.lockAtLeastFor())).isEqualTo(Duration.ofSeconds(1));
        BackgroundFeaturePolicy policy = poll.getAnnotation(BackgroundFeaturePolicy.class);
        assertThat(policy).isNotNull();
        assertThat(policy.mode()).isEqualTo(BackgroundFeatureMode.ALWAYS);
    }

    @Test
    @DisplayName("OB12 回収（毎分）と掃除（日次）は @Scheduled・一意の @SchedulerLock・@BatchEndpoint・@BackgroundFeaturePolicy を持つ")
    void ob12_回収と掃除のバッチの注釈() throws Exception {
        Method recover = NotificationOutboxStuckRecoveryBatch.class.getMethod("recover");
        Method sweep = NotificationOutboxSweepBatch.class.getMethod("sweep");
        Method poll = NotificationOutboxRelay.class.getMethod("poll");

        assertThat(recover.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(recover.getAnnotation(Scheduled.class).cron()).as("毎分").isEqualTo("0 * * * * *");
        assertThat(sweep.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(sweep.getAnnotation(Scheduled.class).cron()).as("日次").isNotBlank();

        assertThat(recover.getAnnotation(BatchEndpoint.class).name())
                .isEqualTo(NotificationOutboxStuckRecoveryBatch.BATCH_NAME);
        assertThat(sweep.getAnnotation(BatchEndpoint.class).name()).isEqualTo(NotificationOutboxSweepBatch.BATCH_NAME);
        assertThat(recover.getAnnotation(BackgroundFeaturePolicy.class)).isNotNull();
        assertThat(sweep.getAnnotation(BackgroundFeaturePolicy.class)).isNotNull();

        Set<String> lockNames = Set.of(
                recover.getAnnotation(SchedulerLock.class).name(),
                sweep.getAnnotation(SchedulerLock.class).name(),
                poll.getAnnotation(SchedulerLock.class).name());
        assertThat(lockNames).as("ポーラー・回収・掃除の lock 名は互いに異なる").hasSize(3);
        assertThat(lockNames).contains("notificationOutboxRelay", "notificationOutboxStuckRecovery",
                "notificationOutboxSweep");
    }

    /** Spring の探索順（メソッド → 宣言クラス → interface のメソッド → interface）で実効の propagation を引く。 */
    private static Propagation effectivePropagation(Method method, Class<?> targetClass) {
        Transactional onMethod = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        if (onMethod != null) {
            return onMethod.propagation();
        }
        Transactional onClass = AnnotatedElementUtils.findMergedAnnotation(targetClass, Transactional.class);
        return onClass == null ? null : onClass.propagation();
    }
}
