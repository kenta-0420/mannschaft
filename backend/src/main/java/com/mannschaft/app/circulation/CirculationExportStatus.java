package com.mannschaft.app.circulation;

/**
 * 回覧文書 PDF エクスポート生成状態。
 *
 * <p>F05.2 Phase 11 第四陣 4-C で追加。
 * {@code circulation_documents.export_status} カラムに ENUM 文字列として保存する。</p>
 *
 * <p>遷移仕様:</p>
 * <ul>
 *   <li>{@link #NOT_GENERATED} — 初期状態（まだ一度もエクスポート要求がない）</li>
 *   <li>{@link #PENDING} — 非同期生成ジョブを受け付けた直後</li>
 *   <li>{@link #COMPLETED} — 生成完了。{@code export_file_key} と {@code export_completed_at} がセット</li>
 *   <li>{@link #FAILED} — 生成失敗。{@code export_error_message} に要約が記録される</li>
 * </ul>
 *
 * <p>FAILED / COMPLETED からは再度 {@link #PENDING} への遷移（再生成）を許容する。</p>
 */
public enum CirculationExportStatus {

    /** 未生成（初期状態）。 */
    NOT_GENERATED,

    /** 非同期生成ジョブ実行中。 */
    PENDING,

    /** 生成完了（R2 にアップロード済み）。 */
    COMPLETED,

    /** 生成失敗。 */
    FAILED
}
