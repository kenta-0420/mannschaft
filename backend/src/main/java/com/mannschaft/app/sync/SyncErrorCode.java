package com.mannschaft.app.sync;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F11.1 オフライン同期のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum SyncErrorCode implements ErrorCode {

    /** 同期アイテムが空 */
    SYNC_ITEMS_EMPTY("SYNC_001", "同期アイテムが空です", Severity.WARN),

    /** 同期アイテム数が上限超過 */
    SYNC_ITEMS_EXCEEDED("SYNC_002", "同期アイテム数が上限（50件）を超えています", Severity.WARN),

    /** レートリミット超過 */
    SYNC_RATE_LIMITED("SYNC_003", "リクエスト頻度が制限を超えています。しばらく待ってから再試行してください", Severity.WARN),

    /** コンフリクトが見つからない */
    CONFLICT_NOT_FOUND("SYNC_004", "コンフリクトが見つかりません", Severity.WARN),

    /** コンフリクトへのアクセス権限なし */
    CONFLICT_ACCESS_DENIED("SYNC_005", "このコンフリクトへのアクセス権限がありません", Severity.WARN),

    /** コンフリクトは既に解決済み */
    CONFLICT_ALREADY_RESOLVED("SYNC_006", "このコンフリクトは既に解決済みです", Severity.WARN),

    /** MANUAL_MERGE 時にマージデータが未指定 */
    CONFLICT_MERGE_DATA_REQUIRED("SYNC_007", "手動マージの場合はマージデータを指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
