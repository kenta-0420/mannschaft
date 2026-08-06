package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.batch.BatchEndpointExempt;
import com.mannschaft.app.common.batch.PodLocalScheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * バッチ規約番人 {@code ScheduledBatchGuardTest} の判定ロジックを検証するための
 * <b>意図的な違反／正当形</b>を集めた fixture。
 *
 * <p>本クラスは {@code src/test/java} 配下にあり、番人本体は
 * {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} で test 配下を除外しているため、
 * 本番走査に混入することはない。メタテスト {@code ScheduledBatchGuardConditionTest} だけが
 * 本クラスを読み込み、番人の判定ヘルパを直接評価する。</p>
 *
 * <p><b>Spring Bean ではない</b>（{@code @Component} を付けていない）ため、
 * ここに書かれた {@code @Scheduled} が実際に起動されることはない。</p>
 */
@SuppressWarnings("unused")
public class ScheduledBatchFixtureBatch {

    /** 違反: {@code @SchedulerLock} も {@code @PodLocalScheduled} も無い（＝複数 Pod で多重実行される形）。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void missingSchedulerLock() {
        // 本文は空でよい（番人は注釈だけを見る）
    }

    /**
     * 違反（{@code @Repeatable} ケース）: {@code @Scheduled} を 2 つ書いたため javac は
     * {@code @Schedules} コンテナに包んで出力する。{@code isAnnotatedWith(Scheduled.class)} だけを
     * 見る実装ではここが<b>丸ごと素通り</b>する（＝最も見逃したくない複数スケジュールのバッチが漏れる）。
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Scheduled(cron = "0 30 15 * * *")
    public void repeatableScheduledMissingSchedulerLock() {
        // 本文は空でよい
    }

    /** 違反: {@code @SchedulerLock} はあるが {@code lockAtMostFor} が無い（既定 30m への暗黙依存）。 */
    @Scheduled(fixedDelay = 10_000)
    @SchedulerLock(name = "fixtureLockWithoutLockAtMostFor")
    @BatchEndpoint(name = "fixture-lock-without-at-most-for")
    public void schedulerLockWithoutLockAtMostFor() {
        // 本文は空でよい
    }

    /** 違反: ロックは正しいが {@code @BatchEndpoint} が無い（名前で起動できず実行履歴も残らない）。 */
    @Scheduled(cron = "0 15 4 * * *")
    @SchedulerLock(name = "fixtureMissingBatchEndpoint", lockAtMostFor = "PT10M")
    public void missingBatchEndpoint() {
        // 本文は空でよい
    }

    /** 正当形: ロック・{@code lockAtMostFor}・{@code @BatchEndpoint} をすべて備える。 */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "fixtureFullyCompliant", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @BatchEndpoint(name = "fixture-fully-compliant", description = "規約を満たす模範形")
    public void fullyCompliant() {
        // 本文は空でよい
    }

    /**
     * 正当形: Pod ローカル実行が設計意図であることを監査済みマーカーで宣言している。
     *
     * <p>ロックを掛けると敗者 Pod のバッファが永久に flush されないため、
     * ロックを付けないことが正しい（実コードの {@code SsrErrorFlushBatch} と同型）。</p>
     */
    @PodLocalScheduled("Pod ローカルのメモリバッファを flush するため、"
        + "ロックを掛けると敗者 Pod のバッファが永久に残る")
    @Scheduled(fixedDelay = 300_000)
    @BatchEndpoint(name = "fixture-pod-local-flush")
    public void podLocalScheduled() {
        // 本文は空でよい
    }

    /**
     * 正当形: 高頻度ワーカーゆえ実行履歴へ登録しないことを監査済みマーカーで宣言している。
     *
     * <p>実コードの {@code EmailOutboxWorker#poll} と同型。</p>
     */
    @BatchEndpointExempt("5 秒間隔の高頻度ワーカーであり、実行履歴を書くと"
        + "日次・月次バッチの記録が埋没するため")
    @Scheduled(fixedDelay = 5_000)
    @SchedulerLock(name = "fixtureHighFrequencyWorker", lockAtMostFor = "PT5M")
    public void batchEndpointExempt() {
        // 本文は空でよい
    }

    /** 対象外: そもそもスケジュールされていない普通のメソッド（巻き込んではならない）。 */
    public void notScheduledAtAll() {
        // 本文は空でよい
    }
}
