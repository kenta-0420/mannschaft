package com.mannschaft.app.moduleevent.e2e;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.stream.Stream;

/**
 * JDK だけで実行する M21 専用の有限試練。DB・Spring・サーバーを起動しない。
 * main は独立実行用。各故障で後続回収と元の例外を観測し、実機合格の代用にはしない。
 */
public final class ModuleEventRealE2eCleanupTest {
    private static final List<String> STEPS = List.of("helper", "gates", "child", "workers", "data", "context", "result");

    private ModuleEventRealE2eCleanupTest() { }

    public static void main(String[] args) throws Throwable {
        if (args.length == 1 && args[0].equals("--child")) {
            new java.util.concurrent.CountDownLatch(1).await(10, TimeUnit.SECONDS);
            return;
        }
        正常終了では全段階と結果保存を試す();
        各回収失敗でも安全な後続と結果保存を試す();
        主例外と副例外の発生順を保持する();
        Errorでも終了処理と結果保存を試す();
        中断は回収待機の間だけ退避して最後に復元する();
        固定三住民以外の資格情報を渡さない();
        固定住民の欠落と重複を拒否する();
        自分で開始したprocessの終了を確認する();
        所有世代不明でも保持processの回収を続ける();
        世代不一致でも保持processの回収を続ける();
        helperの早期終了を成功にしない();
        process停止失敗でも強制停止と全streamのcloseを試す();
        未起動processを許容する();
        // CLI の終了コードに加え実行件数を呼出元が確認する。
        System.out.println("M21 cleanup: 20 cases passed");
    }

    private static void 正常終了では全段階と結果保存を試す() {
        List<String> attempts = new ArrayList<>();
        var cleanup = execute(null, -1, attempts);

        check(attempts.equals(STEPS), "正常時の回収順序: " + attempts);
        check(cleanup.problem() == null, "正常時に失敗がない");
    }

    private static void 各回収失敗でも安全な後続と結果保存を試す() {
        for (int failed = 0; failed < STEPS.size(); failed++) {
            List<String> attempts = new ArrayList<>();
            var cleanup = execute(null, failed, attempts);
            List<String> expected = new ArrayList<>(STEPS);
            if (failed < 4) expected.remove("data");

            check(attempts.equals(expected), "故障 " + STEPS.get(failed) + " の後続回収: " + attempts);
            check(cleanup.problem() != null, "故障を成功にしない");
            if (failed < 4) {
                check(cleanup.stages().contains("data:SKIPPED:回収前提が未確認"), "危険なDB掃除の未実行理由");
            }
        }
    }

    private static void 主例外と副例外の発生順を保持する() {
        Throwable primary = new IllegalArgumentException("実行失敗");
        Throwable first = new IllegalStateException("helper失敗");
        Throwable second = new IllegalStateException("close失敗");
        Throwable save = new IllegalStateException("保存失敗");
        List<String> attempts = new ArrayList<>();
        var cleanup = ModuleEventRealE2eCleanup.finish(primary, () -> { throw first; },
                () -> attempts.add("gates"), () -> attempts.add("child"), () -> attempts.add("workers"),
                () -> attempts.add("data"), () -> { attempts.add("context"); throw second; },
                state -> { attempts.add("result"); throw save; });

        check(cleanup.problem() == primary, "primary identity");
        Throwable[] suppressed = primary.getSuppressed();
        check(suppressed.length == 4 && suppressed[0] == first
                && suppressed[1].getMessage().contains("回収前提が未確認")
                && suppressed[2] == second && suppressed[3] == save, "secondary順序");
        check(attempts.equals(List.of("gates", "child", "workers", "context", "result")), "全安全段階を試行");
    }

    private static void Errorでも終了処理と結果保存を試す() {
        Error error = new AssertionError("回収Error");
        List<String> attempts = new ArrayList<>();
        var cleanup = ModuleEventRealE2eCleanup.finish(null, () -> { throw error; }, () -> { },
                () -> { }, () -> { }, () -> attempts.add("data"), () -> attempts.add("context"),
                state -> attempts.add("result"));

        check(cleanup.problem() == error, "Errorを元のまま保持");
        check(attempts.equals(List.of("context", "result")), "Error後もcloseと保存");
    }

    private static void 中断は回収待機の間だけ退避して最後に復元する() {
        InterruptedException primary = new InterruptedException("本処理中断");
        Thread.currentThread().interrupt();
        try {
            var cleanup = ModuleEventRealE2eCleanup.finish(primary,
                    () -> check(!Thread.currentThread().isInterrupted(), "待機前に退避"),
                    () -> { throw new InterruptedException("回収中断"); },
                    () -> check(!Thread.currentThread().isInterrupted(), "後続待機を試行"),
                    () -> { }, () -> { }, () -> { }, state -> { });

            check(cleanup.problem() == primary, "中断primaryを保持");
            check(Thread.currentThread().isInterrupted(), "最後に中断を復元");
        } finally {
            Thread.interrupted();
        }
    }

    private static ModuleEventRealE2eCleanup execute(Throwable primary, int failed, List<String> attempts) {
        return ModuleEventRealE2eCleanup.finish(primary, step(0, failed, attempts), step(1, failed, attempts),
                step(2, failed, attempts), step(3, failed, attempts), step(4, failed, attempts),
                step(5, failed, attempts), state -> step(6, failed, attempts).run());
    }

    private static ModuleEventRealE2eCleanup.Action step(int index, int failed, List<String> attempts) {
        return () -> {
            attempts.add(STEPS.get(index));
            if (index == failed) throw new IllegalStateException(STEPS.get(index));
        };
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void 固定三住民以外の資格情報を渡さない() {
        List<String> actors = List.of("system", "admin-ja", "admin-en", "admin-zh", "admin-ko", "admin-es",
                "admin-de", "member", "org-admin", "outsider", "revoked");

        var residents = ModuleEventRealE2eCleanup.residentCredentials(actors, code -> code);

        check(residents.equals(List.of("system", "admin-de", "member")), "探索資格情報の固定集合");
        check(actors.size() == 11, "正式spec用の11actorを変更しない");
    }

    private static void 固定住民の欠落と重複を拒否する() {
        for (var actors : List.of(List.of("system", "member"), List.of("system", "admin-de", "member", "member"))) {
            Throwable failure = capture(() -> ModuleEventRealE2eCleanup.residentCredentials(actors, code -> code));

            check(failure instanceof IllegalStateException, "固定住民の不足・重複を拒否");
        }
    }

    private static void 自分で開始したprocessの終了を確認する() throws Throwable {
        Process child = startChild();
        try {
            Instant creation = child.info().startInstant().orElseThrow();

            ModuleEventRealE2eCleanup.stopOwnedProcess(child, creation, false);

            check(!child.isAlive(), "自己processの実exit");
            // 終了後のinfo欠落を所有不明と誤認しない。保持したProcessのexitを再確認する。
            ModuleEventRealE2eCleanup.stopOwnedProcess(child, creation, false);
        } finally {
            reclaimChild(child);
        }
    }

    private static void 所有世代不明でも保持processの回収を続ける() throws Exception {
        Process child = startChild();
        try {
            Throwable failure = capture(() -> ModuleEventRealE2eCleanup.stopOwnedProcess(child, null, false));

            check(failure instanceof IllegalStateException, "世代不明はnonGreen");
            check(!child.isAlive(), "世代不明でも自己handleを回収");
        } finally {
            reclaimChild(child);
        }
    }

    private static void 世代不一致でも保持processの回収を続ける() throws Exception {
        Process child = startChild();
        try {
            Instant mismatch = child.info().startInstant().orElseThrow().minusSeconds(1);
            Throwable failure = capture(() -> ModuleEventRealE2eCleanup.stopOwnedProcess(child, mismatch, false));

            check(failure instanceof IllegalStateException, "世代違いはnonGreen");
            check(!child.isAlive(), "世代違いでも自己handleを回収");
        } finally {
            reclaimChild(child);
        }
    }

    private static void process停止失敗でも強制停止と全streamのcloseを試す() {
        BrokenProcess process = new BrokenProcess();

        Throwable failure = capture(() -> ModuleEventRealE2eCleanup.stopOwnedProcess(process, null, true));

        check(failure instanceof IllegalStateException, "所有不明は最初の失敗");
        check(process.attempts.equals(List.of("descendants", "destroy", "wait", "force", "wait", "stdin", "stdout", "stderr")),
                "一覧取得/停止/期限/close故障の後続を試行: " + process.attempts);
        check(failure.getSuppressed().length == 6, "子一覧・停止・通常期限・強制期限・2close失敗を保持");
    }

    private static void 未起動processを許容する() throws Throwable {
        ModuleEventRealE2eCleanup.stopOwnedProcess(null, null, true);
    }

    private static void helperの早期終了を成功にしない() throws Throwable {
        Process child = startChild();
        try {
            Instant creation = child.info().startInstant().orElseThrow();
            ModuleEventRealE2eCleanup.stopOwnedProcess(child, creation, false);

            Throwable failure = capture(() -> ModuleEventRealE2eCleanup.stopOwnedProcess(child, creation, true));

            check(failure instanceof IllegalStateException && failure.getMessage().contains("停止指示前"),
                    "helperの早期終了はnonGreen");
        } finally {
            reclaimChild(child);
        }
    }

    private static Process startChild() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(executable, "-Xmx32m", "-cp", System.getProperty("java.class.path"),
                ModuleEventRealE2eCleanupTest.class.getName(), "--child")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void reclaimChild(Process child) throws Exception {
        if (child.isAlive()) child.destroyForcibly();
        check(child.waitFor(2, TimeUnit.SECONDS), "試練自己子が残留していない");
        child.getOutputStream().close();
        child.getInputStream().close();
        child.getErrorStream().close();
    }

    private static Throwable capture(ModuleEventRealE2eCleanup.Action action) {
        try { action.run(); return null; } catch (Throwable failure) { return failure; }
    }

    /** 停止とstream故障を有限に注入する自己Process代役。OS上のPIDを扱わない。 */
    private static final class BrokenProcess extends Process {
        private final List<String> attempts = new ArrayList<>();
        @Override public Stream<ProcessHandle> descendants() {
            attempts.add("descendants"); throw new IllegalStateException("子一覧失敗");
        }
        @Override public void destroy() { attempts.add("destroy"); throw new IllegalStateException("停止失敗"); }
        @Override public Process destroyForcibly() { attempts.add("force"); return this; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { attempts.add("wait"); return false; }
        @Override public int waitFor() { throw new AssertionError("無期限待機は禁止"); }
        @Override public int exitValue() { throw new IllegalThreadStateException(); }
        @Override public OutputStream getOutputStream() {
            return new java.io.ByteArrayOutputStream() {
                @Override public void close() { attempts.add("stdin"); throw new IllegalStateException("stdin故障"); }
            };
        }
        @Override public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(new byte[0]) {
                @Override public void close() { attempts.add("stdout"); throw new IllegalStateException("stdout故障"); }
            };
        }
        @Override public InputStream getErrorStream() {
            return new java.io.ByteArrayInputStream(new byte[0]) {
                @Override public void close() { attempts.add("stderr"); }
            };
        }
    }
}
