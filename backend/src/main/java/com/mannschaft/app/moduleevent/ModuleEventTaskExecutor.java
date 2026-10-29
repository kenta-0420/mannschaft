package com.mannschaft.app.moduleevent;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** M専用の受付と世代搬送を検証するexecutor。既存Beanへの接続は別段階で行う。 */
public final class ModuleEventTaskExecutor extends ThreadPoolTaskExecutor {
    private final ModuleEventExecutionFrame.Context context;
    private final ModuleEventExecutionFrame.Owner owner;
    private final Semaphore capacity;
    private final ThreadLocal<Admission> submitting = new ThreadLocal<>();
    private final Map<Runnable, Admission> queued = new ConcurrentHashMap<>();

    public ModuleEventTaskExecutor(ModuleEventExecutionFrame.Context context,
            ModuleEventExecutionFrame.Owner owner, int admissionLimit, TaskDecorator delegate) {
        this.context = context;
        this.owner = owner;
        if (admissionLimit < 1) throw new IllegalArgumentException("受付容量は正数です");
        this.capacity = new Semaphore(admissionLimit);
        setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        super.setTaskDecorator(task -> {
            Admission admission = submitting.get();
            if (admission == null) throw new IllegalStateException("M専用受付を経由していません");
            admission.original = task;
            Runnable decorated = delegate.decorate(task);
            Runnable wrapper = () -> admission.run(decorated);
            admission.wrapper = wrapper;
            queued.put(wrapper, admission);
            return wrapper;
        });
    }

    public int availableCapacity() {
        return capacity.availablePermits();
    }

    @Override public void execute(Runnable task) {
        dispatch(() -> { super.execute(task); return null; });
    }

    @Override public Future<?> submit(Runnable task) {
        return dispatch(() -> super.submit(task));
    }

    @Override public <T> Future<T> submit(Callable<T> task) {
        return dispatch(() -> super.submit(task));
    }

    private <T> T dispatch(Supplier<T> submission) {
        ModuleEventExecutionFrame frame = context.submission().orElse(null);
        if (!capacity.tryAcquire()) {
            if (frame != null) owner.rejected(frame);
            throw new TaskRejectedException("M専用executorの受付上限です");
        }
        Admission admission = new Admission(frame);
        Admission previous = submitting.get();
        submitting.set(admission);
        try {
            return submission.get();
        } catch (RuntimeException | Error failure) {
            try { admission.reject(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            if (previous == null) submitting.remove();
            else submitting.set(previous);
        }
    }

    @Override protected void cancelRemainingTask(Runnable task) {
        Admission admission = queued.get(task);
        if (admission != null) admission.reject();
        super.cancelRemainingTask(task);
    }

    private final class Admission {
        private final ModuleEventExecutionFrame frame;
        // 0=未実行、1=worker所有、2=解放済み。拒否とworker終了の二重解放を防ぐ。
        private final AtomicInteger state = new AtomicInteger();
        private Runnable original;
        private Runnable wrapper;
        private Admission(ModuleEventExecutionFrame frame) { this.frame = frame; }

        private void run(Runnable task) {
            if (!state.compareAndSet(0, 1)) return;
            try {
                if (original instanceof Future<?> future && future.isCancelled()) {
                    if (frame != null) owner.rejected(frame);
                    return;
                }
                if (frame == null) {
                    task.run();
                } else {
                    Throwable executionFailure = null;
                    try (var ignored = context.executing(frame)) {
                        // ここで短M TXをcommitしてから業務へ入る。最新mapを取り直さない。
                        if (owner.admit(frame)) task.run();
                        else cancelOriginal();
                    } catch (RuntimeException | Error failure) {
                        executionFailure = failure;
                        // gateがthrowした場合も、まだrunしていないFutureを未完了に残さない。
                        try { cancelOriginal(); } catch (RuntimeException | Error cleanup) {
                            if (failure != cleanup) failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    } finally {
                        try { owner.finished(frame); } catch (RuntimeException | Error cleanup) {
                            if (executionFailure == null) throw cleanup;
                            if (executionFailure != cleanup) executionFailure.addSuppressed(cleanup);
                        }
                    }
                }
            } finally {
                state.set(2);
                free();
            }
        }

        private void reject() {
            if (!state.compareAndSet(0, 2)) return;
            try {
                cancelOriginal();
                if (frame != null) owner.rejected(frame);
            } finally {
                free();
            }
        }

        private void cancelOriginal() {
            if (original instanceof Future<?> future) future.cancel(false);
        }

        private void free() {
            if (wrapper != null) queued.remove(wrapper, this);
            capacity.release();
        }
    }
}
