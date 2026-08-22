package com.mannschaft.app.admin;

/**
 * バッチジョブのステータス。
 *
 * <p>Gate 基盤工事④-A で {@link #RESUMED} を追加。
 * F10.X 第一陣（バッチ実機検証基盤）で {@link #SKIPPED} を追加。
 * 第一陣時点では使用箇所はないが、第二陣以降の shedlock 取得失敗時用に予約する。
 * batch_job_logs.status は VARCHAR(20) のため、DDL の ALTER は不要。</p>
 */
public enum BatchJobStatus {
    /** 実行中 */
    RUNNING,
    /** 成功 */
    SUCCESS,
    /** 失敗 */
    FAILED,
    /** スキップ（shedlock 取得失敗等で実行されなかった場合）— F10.X 第一陣で追加、第二陣で運用開始予定 */
    SKIPPED,
    /**
     * フィーチャーフラグが有効化され、停止していたバッチが再開した目印（Gate 基盤工事④-A で追加）。
     *
     * <p>{@code BackgroundFeatureSkipRecorder} が「スキップ → 実行」の変わり目に 1 行だけ書く。
     * この行は<b>実行そのものではなく境界の目印</b>である（実際の実行は直後に
     * {@code BatchExecutionAspect} が RUNNING/SUCCESS として別行に記録する）。</p>
     *
     * <p><b>{@link #SUCCESS} で代用してはならない。</b> {@code processedCount=0} の実行が
     * 1 回あったように読め、実績を捏造する。<b>{@link #SKIPPED} で代用してもならない。</b>
     * 停止と再開が同じ値になると、直近 1 行から「今スキップ中か」を読み取れなくなり、
     * {@code BackgroundFeatureSkipRecorder} の状態判定そのものが成り立たない。</p>
     *
     * <p>{@code batch_job_logs.status} は VARCHAR(20) のため DDL の ALTER は不要。</p>
     *
     * <h2>⚠️ 初回本番デプロイより前に「二段階展開」の作法を確立すること</h2>
     * <p><b>本定数を書き込む新タスクと、本定数を知らない旧タスクが併存すると、旧タスク側が
     * 壊れる。</b> 本番はローリング更新であり（{@code infra/terraform/modules/app/main.tf}）、
     * 更新中は新旧のタスクが同時に動く。{@code status} は
     * {@code @Enumerated(EnumType.STRING)} で読まれるため、旧タスクが {@code "RESUMED"} の行を
     * 読むと列挙定数へ変換できず例外になり、履歴一覧やステータス API が 500 になる。
     * <b>ロールバックしても、書かれてしまった行が残る限り障害は継続する。</b></p>
     *
     * <p>したがって初回本番デプロイより前に、<b>①定数を配布するだけのリリース</b>と
     * <b>②その定数を書き始めるリリース</b>を分ける作法を確立しなければならない
     * （これは本定数に限らず、<b>永続化される enum に定数を足す行為全般</b>の問題である）。</p>
     *
     * <p>本 PR で二段階展開を実装しなかったのは、<b>本プロジェクトがまだ一度も本番デプロイして
     * おらず、{@code batch_job_logs} に実データも無い</b>ため、旧バイナリ併存という前提自体が
     * 存在しないからである（マスター裁可）。<b>初回デプロイ以降は実在するリスク</b>であり、
     * 課題として {@code docs/task-list.md} に起票済み（CMP-260822-1026）。
     * この危険は Codex による独立検分（4 巡目）で指摘された事実である。</p>
     */
    RESUMED
}
