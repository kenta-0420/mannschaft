package com.mannschaft.app.moduleevent;

import java.util.Optional;
import java.util.UUID;

/** Asyncへ投入する一世代の識別情報。callbackで最新世代を取り直さない。 */
public record ModuleEventExecutionFrame(Object event, UUID publicationId, UUID attemptId,
        UUID token, String listenerId, boolean initial) {

    /** 業務入口の短TXと、拒否・終了の世代付き通知を本体へ委譲する内部契約。 */
    public interface Owner {
        boolean admit(ModuleEventExecutionFrame frame);
        void rejected(ModuleEventExecutionFrame frame);
        void finished(ModuleEventExecutionFrame frame);
    }

    /** 初回の未投入登録と、producer/workerの局所frameを保持する。 */
    public static final class Context {
        public void register(ModuleEventExecutionFrame frame) {
        }

        public Optional<ModuleEventExecutionFrame> capture(Object event, String listenerId) {
            return Optional.empty();
        }

        public boolean release(ModuleEventExecutionFrame frame, Runnable trackerCleanup) {
            return false;
        }

        public boolean expire(ModuleEventExecutionFrame frame, Runnable trackerCleanup) {
            return false;
        }

        public Optional<ModuleEventExecutionFrame> submission() {
            return Optional.empty();
        }

        public Optional<ModuleEventExecutionFrame> execution() {
            return Optional.empty();
        }

        public Scope submitting(ModuleEventExecutionFrame frame) {
            return () -> {};
        }

        public Scope executing(ModuleEventExecutionFrame frame) {
            return () -> {};
        }
    }

    /** 呼出元のframeを必ず復元する。 */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
