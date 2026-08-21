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
     */
    RESUMED
}
