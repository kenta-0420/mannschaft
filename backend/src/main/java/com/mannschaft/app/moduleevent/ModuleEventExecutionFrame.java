package com.mannschaft.app.moduleevent;

import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Asyncへ投入する一世代の識別情報。callbackで最新世代を取り直さない。 */
public record ModuleEventExecutionFrame(Object event, UUID publicationId, UUID attemptId,
        UUID token, String listenerId, boolean initial) {
    public ModuleEventExecutionFrame {
        Objects.requireNonNull(event);
        Objects.requireNonNull(publicationId);
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(token);
        if (listenerId == null || listenerId.isBlank()) {
            throw new IllegalArgumentException("購読IDは必須です");
        }
    }

    /** 業務入口の短TXと、拒否・終了の世代付き通知を本体へ委譲する内部契約。 */
    public interface Owner {
        boolean admit(ModuleEventExecutionFrame frame);
        void rejected(ModuleEventExecutionFrame frame);
        void finished(ModuleEventExecutionFrame frame);
    }

    /** 初回の未投入登録と、producer/workerの局所frameを保持する。 */
    public static final class Context {
        private final Map<InvocationKey, Registration> registrations = new HashMap<>();
        private final ThreadLocal<ModuleEventExecutionFrame> submission = new ThreadLocal<>();
        private final ThreadLocal<ModuleEventExecutionFrame> execution = new ThreadLocal<>();

        /** 全対象の所有確認は公式登録前に同じmonitor内で行う。 */
        synchronized void assertUnregistered(Object event, Collection<String> listenerIds) {
            for (String listenerId : listenerIds) {
                if (registrations.containsKey(new InvocationKey(event, listenerId))) {
                    throw new IllegalStateException("同じイベント実体と購読IDの登録が未解放です");
                }
            }
        }

        synchronized boolean wasCaptured(ModuleEventExecutionFrame frame) {
            Registration current = registrations.get(new InvocationKey(frame.event(), frame.listenerId()));
            return current != null && current.frame == frame && current.captured;
        }

        /** DB claimで置換済みの旧tokenだけを掃除する。未submit初回は置換しない。 */
        synchronized void expirePriorGenerations(UUID publicationId, UUID newToken,
                Consumer<ModuleEventExecutionFrame> trackerCleanup) {
            for (Registration registration : List.copyOf(registrations.values())) {
                ModuleEventExecutionFrame frame = registration.frame;
                if (registration.captured && publicationId.equals(frame.publicationId())
                        && !newToken.equals(frame.token())) {
                    release(frame, () -> trackerCleanup.accept(frame));
                }
            }
        }

        public synchronized void register(ModuleEventExecutionFrame frame) {
            InvocationKey key = new InvocationKey(frame.event(), frame.listenerId());
            if (registrations.containsKey(key)) {
                throw new IllegalStateException("同じイベント実体と購読IDの登録が未解放です");
            }
            registrations.put(key, new Registration(frame));
        }

        public synchronized Optional<ModuleEventExecutionFrame> capture(Object event, String listenerId) {
            Registration registration = registrations.get(new InvocationKey(event, listenerId));
            if (registration == null) {
                return Optional.empty();
            }
            if (registration.captured) {
                throw new IllegalStateException("同じ世代を複数回投入できません");
            }
            registration.captured = true;
            return Optional.of(registration.frame);
        }

        public synchronized boolean release(ModuleEventExecutionFrame frame, Runnable trackerCleanup) {
            InvocationKey key = new InvocationKey(frame.event(), frame.listenerId());
            Registration current = registrations.get(key);
            if (current == null || current.frame != frame) {
                return false;
            }
            // 新世代が割り込む前に、同じ所有確認の内側で公式trackerを解除する。
            try {
                trackerCleanup.run();
            } finally {
                registrations.remove(key, current);
            }
            return true;
        }

        public synchronized boolean expire(ModuleEventExecutionFrame frame, Runnable trackerCleanup) {
            Registration current = registrations.get(new InvocationKey(frame.event(), frame.listenerId()));
            // AFTER_COMMITがまだsubmitへ達していない初回登録をTTLで取り替えない。
            return current != null && current.frame == frame && current.captured
                    && release(frame, trackerCleanup);
        }

        public Optional<ModuleEventExecutionFrame> submission() {
            return Optional.ofNullable(submission.get());
        }

        public Optional<ModuleEventExecutionFrame> execution() {
            return Optional.ofNullable(execution.get());
        }

        public Scope submitting(ModuleEventExecutionFrame frame) {
            return install(submission, frame);
        }

        public Scope executing(ModuleEventExecutionFrame frame) {
            return install(execution, frame);
        }

        private Scope install(ThreadLocal<ModuleEventExecutionFrame> storage, ModuleEventExecutionFrame frame) {
            ModuleEventExecutionFrame previous = storage.get();
            storage.set(frame);
            return () -> {
                if (previous == null) storage.remove();
                else storage.set(previous);
            };
        }

        private static final class Registration {
            private final ModuleEventExecutionFrame frame;
            private boolean captured;
            private Registration(ModuleEventExecutionFrame frame) { this.frame = frame; }
        }

        private static final class InvocationKey {
            private final Object event;
            private final String listenerId;
            private InvocationKey(Object event, String listenerId) {
                this.event = event;
                this.listenerId = listenerId;
            }
            @Override public boolean equals(Object other) {
                return other instanceof InvocationKey key && event == key.event && listenerId.equals(key.listenerId);
            }
            @Override public int hashCode() { return 31 * System.identityHashCode(event) + listenerId.hashCode(); }
        }
    }

    /** 呼出元のframeを必ず復元する。 */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
