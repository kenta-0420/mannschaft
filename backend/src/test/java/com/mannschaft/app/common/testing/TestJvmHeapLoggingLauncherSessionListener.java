package com.mannschaft.app.common.testing;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * テストワーカー JVM の実効最大ヒープ（-Xmx 等で確定した値）を CI ログへ1行出力する。
 *
 * <p>CMP-261002-1606（backend CI shard 5 の OOM）の証跡整備の一環。build.gradle.kts の
 * Test タスクで -Xmx4g を指定しているにもかかわらず、OOM 時のヒープダンプが約7GBになる
 * 事例が観測されており、その原因調査（JVM 側の実効値が本当に 4g なのか）には、
 * 各ワーカー JVM 自身が報告する {@link Runtime#maxMemory()} の実測値が必要になる。
 *
 * <p>{@code addTestListener} による Gradle の TestListener は Gradle デーモン（ビルド側）
 * プロセスで実行されるため、ワーカー JVM 自身のヒープ設定値を読むことができない
 * （build.gradle.kts 428行目付近の [test-jvm] 計測器のコメント参照）。本クラスは
 * {@link LauncherSessionListener} として ServiceLoader
 * （{@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}）経由で
 * JUnit Platform Launcher に登録され、ワーカー JVM 自身の中で起動直後に1回実行されるため、
 * Gradle 側からは原理的に見えない「このワーカー JVM が実際に確保した maxMemory」を測れる。
 *
 * <p>出力は {@code [test-jvm-heap] pid=... maxMemory=...MB} 形式に固定し、CI ログを
 * この接頭辞で grep すれば全ワーカー分の実測値を一覧できるようにする。
 */
public class TestJvmHeapLoggingLauncherSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long pid = ProcessHandle.current().pid();
        // System.out を使う（SLF4J 等のロガーはテストJVM起動直後にまだ初期化されておらず、
        // ログ設定次第で欠落する可能性があるため、Gradle がそのまま captureする標準出力に出す）。
        System.out.println("[test-jvm-heap] pid=" + pid + " maxMemory=" + maxMemoryMb + "MB");
    }
}
