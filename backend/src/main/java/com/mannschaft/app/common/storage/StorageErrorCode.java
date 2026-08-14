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
    /**
     * S3/R2 からのファイル削除に失敗した（外部ストレージ障害）。
     *
     * <p>兄弟の UPLOAD_FAILED/DOWNLOAD_FAILED/PRESIGNED_URL_FAILED は全て Severity.ERROR
     * なのにこの定数だけ Severity.WARN で定義されており、未登録のため既定 400（クライアント
     * 起因）で返っていた。throw元（S3StorageService/R2StorageService）はいずれもクライアント
     * 入力ではなく外部ストレージ呼び出しの失敗であり、全数調査で定義側の誤分類と判明したため
     * ERROR（既定500）に是正する。</p>
     */
    DELETE_FAILED("STORAGE_003", "ファイルの削除に失敗しました", Severity.ERROR),
    PRESIGNED_URL_FAILED("STORAGE_004", "署名付きURLの生成に失敗しました", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
