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

    // ══════════════════════════════════════════════════════════════════════
    // ルール 4（lockAtMostFor > 実行間隔）用の fixture
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 違反（最も危険な同値）: 5 分間隔の cron に対し {@code lockAtMostFor} が
     * ぴったり 5 分。実行が 5 分を超えた瞬間にロックが失効し、同時刻の次周回と重なる。
     */
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "fixtureLockEqualToInterval", lockAtMostFor = "5m")
    @BatchEndpoint(name = "fixture-lock-equal-to-interval")
    public void lockAtMostForEqualToCronInterval() {
        // 本文は空でよい
    }

    /** 違反: 1 分間隔の cron に対し {@code lockAtMostFor} が 30 秒（間隔より短い）。 */
    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "fixtureLockShorterThanInterval", lockAtMostFor = "PT30S")
    @BatchEndpoint(name = "fixture-lock-shorter-than-interval")
    public void lockAtMostForShorterThanCronInterval() {
        // 本文は空でよい
    }

    /** 違反: {@code fixedRate}（ミリ秒数値）60 秒に対し {@code lockAtMostFor} が 60 秒（同値）。 */
    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "fixtureFixedRateEqual", lockAtMostFor = "60s")
    @BatchEndpoint(name = "fixture-fixed-rate-equal")
    public void lockAtMostForEqualToFixedRate() {
        // 本文は空でよい
    }

    /** 違反: {@code fixedDelay} 5 分に対し {@code lockAtMostFor} が 4 分（間隔より短い）。 */
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "fixtureFixedDelayShorter", lockAtMostFor = "PT4M")
    @BatchEndpoint(name = "fixture-fixed-delay-shorter")
    public void lockAtMostForShorterThanFixedDelay() {
        // 本文は空でよい
    }

    /**
     * 違反（判定不能）: cron が<b>既定値の無いプレースホルダ</b>であり、CI からは実際の
     * 起動間隔を確認できない。安全側に倒して落とす（外部プロパティへの追い出しが
     * 番人の抜け道にならないようにするため）。
     */
    @Scheduled(cron = "${fixture.undecidable.cron}")
    @SchedulerLock(name = "fixtureUndecidableCron", lockAtMostFor = "PT1M")
    @BatchEndpoint(name = "fixture-undecidable-cron")
    public void undecidableCronPlaceholder() {
        // 本文は空でよい
    }

    /**
     * 違反（{@code @Repeatable} ケース）: 日次（安全）と 5 分間隔（危険）の 2 本を持ち、
     * {@code lockAtMostFor} は 5 分間隔側に対して不足している。
     * {@code @Schedules} コンテナを開かない実装はこの形を丸ごと取り逃す。
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "fixtureRepeatableInsufficientLock", lockAtMostFor = "PT3M")
    @BatchEndpoint(name = "fixture-repeatable-insufficient-lock")
    public void repeatableScheduledWithInsufficientLock() {
        // 本文は空でよい
    }

    /** 正当形: 5 分間隔の cron に対し {@code lockAtMostFor} が 10 分（間隔を上回る）。 */
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "fixtureLockExceedsInterval", lockAtMostFor = "PT10M")
    @BatchEndpoint(name = "fixture-lock-exceeds-interval")
    public void lockAtMostForExceedsCronInterval() {
        // 本文は空でよい
    }

    /**
     * 正当形（低頻度バッチ）: 日次 02:00 の cron に対し {@code lockAtMostFor} は 30 分。
     *
     * <p>間隔（24 時間）より短いが、次の起動まで 24 時間あるため
     * 「ロック失効中に次周回が重なる」ことは起こらない。むしろ 24 時間超のロックは
     * Pod 異常終了時にバッチを丸一日停止させるため有害である。
     * 本番人が高頻度バッチだけを対象にしていることの実証。</p>
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "fixtureDailyBatch", lockAtMostFor = "PT30M")
    @BatchEndpoint(name = "fixture-daily-batch")
    public void dailyBatchWithShorterLock() {
        // 本文は空でよい
    }

    /** 正当形: cron が<b>既定値付き</b>プレースホルダで、その既定値から間隔を確認できる。 */
    @Scheduled(cron = "${fixture.resolvable.cron:0 */5 * * * *}")
    @SchedulerLock(name = "fixtureResolvableCron", lockAtMostFor = "PT10M")
    @BatchEndpoint(name = "fixture-resolvable-cron")
    public void resolvableCronPlaceholder() {
        // 本文は空でよい
    }

    /** 対象外: そもそもスケジュールされていない普通のメソッド（巻き込んではならない）。 */
    public void notScheduledAtAll() {
        // 本文は空でよい
    }
}
