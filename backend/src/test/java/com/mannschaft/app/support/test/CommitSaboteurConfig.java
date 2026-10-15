package com.mannschaft.app.support.test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 「publish した後、commit の直前にトランザクションを強制 rollback させる」注入点（Issue #2997 AC-A）。
 *
 * <p>{@code @TransactionalEventListener(BEFORE_COMMIT)} が例外を投げると、その TX は commit されずに rollback される。
 * 対象イベントの単純クラス名を {@link CommitSaboteur#arm} で指定した間だけ有効になり、
 * {@link CommitSaboteur#tripped()} で「注入点に実際に到達したこと」を観測できる
 * （到達していない rollback テストは緑でも無意味なため、必ず確認する）。</p>
 *
 * <p>{@code @Import(CommitSaboteurConfig.class)} で取り込む。</p>
 */
@TestConfiguration
public class CommitSaboteurConfig {

    @Bean
    public CommitSaboteur commitSaboteur() {
        return new CommitSaboteur();
    }

    /** BEFORE_COMMIT で対象イベントに対し例外を投げる注入用 Bean。 */
    public static class CommitSaboteur {
        private final AtomicReference<Set<String>> armedEventNames = new AtomicReference<>(Set.of());
        private final AtomicInteger tripped = new AtomicInteger();

        public void arm(String... simpleEventNames) {
            tripped.set(0);
            armedEventNames.set(Set.of(simpleEventNames));
        }

        public void disarm() {
            armedEventNames.set(Set.of());
        }

        public int tripped() {
            return tripped.get();
        }

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        public void onAnyEvent(Object event) {
            if (armedEventNames.get().contains(event.getClass().getSimpleName())) {
                tripped.incrementAndGet();
                throw new IllegalStateException("BEFORE_COMMIT 注入による強制 rollback（AC-A 検証用）: "
                        + event.getClass().getSimpleName());
            }
        }
    }
}
