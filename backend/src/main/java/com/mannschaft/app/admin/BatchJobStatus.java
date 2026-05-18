package com.mannschaft.app.admin;

/**
 * バッチジョブのステータス。
 *
 * <p>F10.X 第一陣（バッチ実機検証基盤）で {@link #SKIPPED} を追加。
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
    SKIPPED
}
