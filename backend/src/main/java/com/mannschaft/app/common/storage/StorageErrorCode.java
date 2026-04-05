package com.mannschaft.app.common.storage;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ストレージ操作のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum StorageErrorCode implements ErrorCode {

    UPLOAD_FAILED("STORAGE_001", "ファイルのアップロードに失敗しました", Severity.ERROR),
    DOWNLOAD_FAILED("STORAGE_002", "ファイルのダウンロードに失敗しました", Severity.ERROR),
    DELETE_FAILED("STORAGE_003", "ファイルの削除に失敗しました", Severity.WARN),
    PRESIGNED_URL_FAILED("STORAGE_004", "署名付きURLの生成に失敗しました", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
