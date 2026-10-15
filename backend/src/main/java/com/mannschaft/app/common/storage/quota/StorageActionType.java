package com.mannschaft.app.common.storage.quota;

/**
 * F13 ストレージクォータの操作種別（{@code storage_usage_logs.action} に対応）。
 *
 * @see <a href="../../../../../../../docs/cross-cutting/storage_quota.md">設計書 §3</a>
 */
public enum StorageActionType {
    /** ファイルアップロード（使用量 +） */
    UPLOAD,
    /** ファイル削除（使用量 -） */
    DELETE,
    /** バージョン追加（F05.5、使用量 +） */
    VERSION_ADD,
    /** バージョン削除（F05.5、使用量 -） */
    VERSION_DELETE,
    /** ドリフト検出バッチによる自動修正 */
    DRIFT_CORRECTION
}
