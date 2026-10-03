package com.mannschaft.app.receipt;

/**
 * PDF の生成・保存状態（F08.12 §3.1）。
 *
 * <p>従来は {@code pdf_storage_key} の NULL 判定から導出していたため、
 * 「失敗したのか、まだ生成中なのか」を区別できず再試行の判断ができなかった。
 * 証憑の状態機械（{@code ISSUED} / {@code voided_at}）とは独立した軸として列に持つ。</p>
 */
public enum ReceiptPdfStatus {

    /** 生成中（発行直後の初期状態）。 */
    GENERATING,

    /** 原本の保存まで完了。 */
    READY,

    /** 生成または保存に失敗。{@code pdf_attempt_count} が上限に達するまで再試行する。 */
    FAILED
}
