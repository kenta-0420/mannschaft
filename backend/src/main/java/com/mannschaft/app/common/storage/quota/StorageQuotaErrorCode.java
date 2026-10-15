package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F13 ストレージクォータ統合機構のエラーコード。
 *
 * <p>HttpStatus マッピングは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に登録する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum StorageQuotaErrorCode implements ErrorCode {

    /** スコープ別クォータ容量超過（409 Conflict）。 */
    QUOTA_EXCEEDED("STORAGE_QUOTA_001", "ストレージ容量が不足しています", Severity.WARN),

    /** クォータ計上対象のスコープ subscription が見つからない（500 Internal）。
     * 通常は組織・チーム・ユーザー作成時の初期化漏れを示す。 */
    SUBSCRIPTION_NOT_FOUND("STORAGE_QUOTA_002", "ストレージサブスクリプションが見つかりません", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
