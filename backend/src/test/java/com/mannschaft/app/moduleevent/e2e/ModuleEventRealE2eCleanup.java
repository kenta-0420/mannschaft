package com.mannschaft.app.moduleevent.e2e;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** M21 host の後始末だけを順序付ける。製品コードからは使用しない。 */
final class ModuleEventRealE2eCleanup {
    private static final int PROCESS_STOP_SECONDS = 10;
    private Throwable problem;
    private final List<String> stages = new ArrayList<>();
    private boolean interrupted;

    /** M21 の回収操作。Error も最後に元のまま伝播させる。 */
    @FunctionalInterface
    interface Action { void run() throws Throwable; }

    /** 秘密を含まない host 結果の保存操作。 */
    @FunctionalInterface
    interface ResultWriter { void write(ModuleEventRealE2eCleanup cleanup) throws Throwable; }

    ModuleEventRealE2eCleanup(Throwable primary) {
        problem = primary;
        interrupted = Thread.interrupted() || primary instanceof InterruptedException;
    }

    static ModuleEventRealE2eCleanup finish(Throwable primary, Action helper, Action gates,
            Action child, Action workers, Action data, Action context, ResultWriter result) {
        ModuleEventRealE2eCleanup cleanup = new ModuleEventRealE2eCleanup(primary);
        try {
            boolean safe = cleanup.attempt("helper", helper);
            safe &= cleanup.attempt("gates", gates);
            safe &= cleanup.attempt("child", child);
            safe &= cleanup.attempt("workers", workers);
            // 停止未確認の書き手と競合する DB 削除は行わず、未回収を明示する。
            if (safe) cleanup.attempt("data", data);
            else {
                cleanup.stages.add("data:SKIPPED:回収前提が未確認");
                cleanup.record(new IllegalStateException("生成ID cleanup未実行: 回収前提が未確認"));
            }
            cleanup.attempt("context", context);
            cleanup.attempt("result", () -> result.write(cleanup));
        } finally {
            cleanup.restoreInterrupt();
        }
        return cleanup;
    }

    boolean attempt(String stage, Action action) {
        stages.add(stage + ":ATTEMPTED");
        try {
            action.run();
            if (Thread.interrupted()) {
                interrupted = true;
                throw new InterruptedException("M21回収中に中断を検知: " + stage);
            }
            stages.set(stages.size() - 1, stage + ":PASSED");
            return true;
        } catch (Throwable failure) {
            interrupted |= Thread.interrupted() | failure instanceof InterruptedException;
            record(failure);
            stages.set(stages.size() - 1, stage + ":FAILED");
            return false;
        }
    }

    void record(Throwable failure) {
        if (problem == null) problem = failure;
        else if (problem != failure) problem.addSuppressed(failure);
    }

    void restoreInterrupt() {
        if (interrupted) Thread.currentThread().interrupt();
    }

    void throwIfFailed() throws Throwable {
        if (problem != null) throw problem;
    }

    static <T> List<T> residentCredentials(List<T> actors, Function<T, String> code) {
        Set<String> expected = Set.of("system", "admin-de", "member");
        List<T> residents = actors.stream().filter(actor -> expected.contains(code.apply(actor))).toList();
        Set<String> actual = new java.util.HashSet<>();
        for (T actor : residents) {
            if (!actual.add(code.apply(actor))) throw new IllegalStateException("住民actorの重複");
        }
        if (!actual.equals(expected)) throw new IllegalStateException("固定3住民actorの不足");
        return residents;
    }

    /**
     * start() から保持している自己 Process だけを停止する。PID から取得し直さない。
     * 事前に捕捉できた子孫は全件試行するが、孤児・未登録子の不在証明は外側監督の責務。
     * stdout/stderr は host の file/DISCARD redirect が前提で、pipe EOF の代用にはしない。
     */
    static void stopOwnedProcess(Process process, Instant started, boolean requiredAlive) throws Throwable {
        if (process == null) return;
        ModuleEventRealE2eCleanup cleanup = new ModuleEventRealE2eCleanup(null);
        List<ProcessHandle> snapshot = new ArrayList<>();
        List<Descendant> descendants = new ArrayList<>();
        try {
            boolean identity = cleanup.attempt("identity", () -> {
                // 終了済みの自己Processはinfoが空になり得る。保持した開始世代と実exitを使う。
                if (started == null || (process.isAlive()
                        && process.info().startInstant().filter(started::equals).isEmpty())) {
                    throw new IllegalStateException("自己process所有metadata照合失敗");
                }
            });
            cleanup.attempt("required-alive", () -> {
                if (requiredAlive && !process.isAlive()) throw new IllegalStateException("自己helperが停止指示前に終了した");
            });
            // JDK の descendants() は現在の PID snapshot を使い、保持した親世代を比較しない。
            // dead/不明な親では呼ばず、列挙前後とも自己親が live で世代一致した場合だけ採用する。
            boolean stableSnapshot = identity && cleanup.attempt("descendants", () -> {
                if (!process.isAlive()) return;
                requireLiveParent(process, started);
                try (var handles = process.descendants()) { handles.forEach(snapshot::add); }
                requireLiveParent(process, started);
            });
            if (stableSnapshot) {
                for (ProcessHandle handle : snapshot) {
                    cleanup.attempt("descendant-identity", () -> descendants.add(new Descendant(handle,
                            handle.info().startInstant().filter(value -> value.isAfter(Instant.EPOCH))
                                    .orElseThrow(() -> new IllegalStateException("子の開始世代が不明。外側監督で回収する")))));
                }
            }
            for (Descendant descendant : descendants) {
                cleanup.attempt("descendant-stop", () -> {
                    if (descendant.hasVerifiedLiveGeneration()) descendant.handle.destroy();
                });
            }
            cleanup.attempt("process-stop", () -> { if (process.isAlive()) process.destroy(); });
            boolean exited = cleanup.attempt("process-exit", () -> {
                if (!process.waitFor(PROCESS_STOP_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("自己processの通常停止期限超過");
                }
            });
            // 通常停止の期限超過も nonGreen のまま、強制停止と終了確認は必ず試す。
            if (!exited) {
                cleanup.attempt("process-force", process::destroyForcibly);
                cleanup.attempt("process-forced-exit", () -> {
                    if (!process.waitFor(PROCESS_STOP_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("自己processが残留");
                    }
                });
            }
            for (Descendant descendant : descendants) {
                if (!descendant.trusted) continue;
                cleanup.attempt("descendant-force", () -> {
                    if (descendant.hasVerifiedLiveGeneration()) descendant.handle.destroyForcibly();
                });
                if (descendant.trusted) {
                    cleanup.attempt("descendant-exit", () -> descendant.handle.onExit().get(PROCESS_STOP_SECONDS, TimeUnit.SECONDS));
                }
            }
            // stdin の親側 pipe と、redirect により空である親側読取り口も独立に閉じる。
            cleanup.attempt("stdin-close", () -> process.getOutputStream().close());
            cleanup.attempt("stdout-close", () -> process.getInputStream().close());
            cleanup.attempt("stderr-close", () -> process.getErrorStream().close());
        } finally {
            cleanup.restoreInterrupt();
        }
        cleanup.throwIfFailed();
    }

    private static void requireLiveParent(Process process, Instant started) {
        if (!process.isAlive() || started == null
                || process.info().startInstant().filter(started::equals).isEmpty() || !process.isAlive()) {
            throw new IllegalStateException("子の列挙前後に自己親世代を確認できない。外側監督で回収する");
        }
    }

    /** M21 の検証済み snapshot で捕捉した子。世代照合を失ったら停止対象へ戻さない。 */
    private static final class Descendant {
        private final ProcessHandle handle;
        private final Instant started;
        private boolean trusted = true;

        private Descendant(ProcessHandle handle, Instant started) {
            this.handle = handle;
            this.started = started;
        }

        private boolean hasVerifiedLiveGeneration() throws Throwable {
            try {
                if (!handle.isAlive()) return false;
                // JDK21 Info は保持 startTime が 0/不一致なら startInstant を返さない。
                // 既知の正の開始世代を再照合し、native の startTime=0 による比較省略を使わない。
                if (handle.info().startInstant().filter(started::equals).isEmpty()) {
                    throw new IllegalStateException("子の開始世代が不明または不一致。外側監督で回収する");
                }
                return handle.isAlive();
            } catch (Throwable failure) {
                trusted = false;
                throw failure;
            }
        }
    }

    Throwable problem() { return problem; }
    List<String> stages() { return List.copyOf(stages); }
}
