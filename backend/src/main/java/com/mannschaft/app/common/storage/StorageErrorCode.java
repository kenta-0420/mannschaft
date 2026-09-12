package com.mannschaft.app.common.storage;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** ストレージ操作のエラーコード。 */
@Getter
@RequiredArgsConstructor
public enum StorageErrorCode implements ErrorCode {

    UPLOAD_FAILED("STORAGE_001", "ファイルのアップロードに失敗しました", Severity.ERROR),
    DOWNLOAD_FAILED("STORAGE_002", "ファイルのダウンロードに失敗しました", Severity.ERROR),
    DELETE_FAILED("STORAGE_003", "ファイルの削除に失敗しました", Severity.ERROR),
    PRESIGNED_URL_FAILED("STORAGE_004", "署名付きURLの生成に失敗しました", Severity.ERROR),

    /** 404: ACL の対象が存在しない。 */
    ACL_NOT_FOUND("STORAGE_005", "ストレージオブジェクトが見つかりません", Severity.WARN),
    /** 403: 所有者または ACL 所有スコープが一致しない。 */
    ACL_FORBIDDEN("STORAGE_006", "このストレージオブジェクトを操作する権限がありません", Severity.WARN),
    /** 409: claim 済み、期限切れ、または無効な ACL 状態。 */
    ACL_CLAIM_CONFLICT("STORAGE_007", "ストレージオブジェクトを添付先へ束縛できません", Severity.WARN),
    /** 400: ACL スコープまたは入力値が不正。 */
    ACL_INVALID_REQUEST("STORAGE_008", "\u30b9\u30c8\u30ec\u30fc\u30b8\u0041\u0043\u004c\u306e\u5165\u529b\u5024\u304c\u4e0d\u6b63\u3067\u3059", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
