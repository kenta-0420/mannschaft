package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F05.5 ファイル共有のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum FileSharingErrorCode implements ErrorCode {

    /** フォルダが見つからない */
    FOLDER_NOT_FOUND("FILE_SHARING_001", "フォルダが見つかりません", Severity.WARN),

    /** ファイルが見つからない */
    FILE_NOT_FOUND("FILE_SHARING_002", "ファイルが見つかりません", Severity.WARN),

    /** ファイルバージョンが見つからない */
    VERSION_NOT_FOUND("FILE_SHARING_003", "ファイルバージョンが見つかりません", Severity.WARN),

    /** 権限が見つからない */
    PERMISSION_NOT_FOUND("FILE_SHARING_004", "権限が見つかりません", Severity.WARN),

    /** スターが見つからない */
    STAR_NOT_FOUND("FILE_SHARING_005", "スターが見つかりません", Severity.WARN),

    /** コメントが見つからない */
    COMMENT_NOT_FOUND("FILE_SHARING_006", "コメントが見つかりません", Severity.WARN),

    /** 共有リンクが見つからない */
    LINK_NOT_FOUND("FILE_SHARING_007", "共有リンクが見つかりません", Severity.WARN),

    /** タグが見つからない */
    TAG_NOT_FOUND("FILE_SHARING_008", "タグが見つかりません", Severity.WARN),

    /** スター重複 */
    STAR_ALREADY_EXISTS("FILE_SHARING_009", "既にスター済みです", Severity.WARN),

    /** タグ重複 */
    TAG_ALREADY_EXISTS("FILE_SHARING_010", "同名のタグが既に付与されています", Severity.WARN),

    /** 共有リンク期限切れ */
    LINK_EXPIRED("FILE_SHARING_011", "共有リンクの有効期限が切れています", Severity.WARN),

    /** 共有リンクパスワード不正 */
    LINK_PASSWORD_INVALID("FILE_SHARING_012", "パスワードが正しくありません", Severity.WARN),

    /** フォルダ名重複 */
    FOLDER_NAME_DUPLICATE("FILE_SHARING_013", "同名のフォルダが既に存在します", Severity.WARN),

    /** ファイルサイズ超過 */
    FILE_SIZE_EXCEEDED("FILE_SHARING_014", "ファイルサイズの上限を超えています", Severity.ERROR),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("FILE_SHARING_015", "この操作を実行する権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
