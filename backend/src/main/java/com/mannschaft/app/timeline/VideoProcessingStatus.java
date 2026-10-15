package com.mannschaft.app.timeline;

/** VIDEO_FILE 添付ファイルの後処理ステータス。 */
public enum VideoProcessingStatus {
    PENDING,      // アップロード直後（Workers 処理待ち）
    PROCESSING,   // Workers 実行中
    READY,        // 完了（再生可能）
    FAILED        // 失敗（プレースホルダー表示）
}
