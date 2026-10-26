package com.mannschaft.app.scopefolder;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F15.2 マイスコープフォルダ機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ScopeFolderErrorCode implements ErrorCode {

    /** フォルダが存在しない */
    SCOPE_FOLDER_NOT_FOUND("SCOPE_FOLDER_NOT_FOUND", "指定されたフォルダが存在しません", Severity.WARN),

    /** フォルダ所有者でない（IDOR防止） */
    SCOPE_FOLDER_ACCESS_DENIED("SCOPE_FOLDER_ACCESS_DENIED", "このフォルダにアクセスする権限がありません", Severity.WARN),

    /** フォルダ数上限（20件）に到達 */
    SCOPE_FOLDER_LIMIT_EXCEEDED("SCOPE_FOLDER_LIMIT_EXCEEDED", "フォルダの作成上限（20件）に達しています", Severity.WARN),

    /** 同名フォルダが既に存在 */
    SCOPE_FOLDER_NAME_DUPLICATE("SCOPE_FOLDER_NAME_DUPLICATE", "同じ名前のフォルダが既に存在します", Severity.WARN),

    /** 対象スコープ（チーム/組織）に所属していない */
    SCOPE_FOLDER_NOT_MEMBER("SCOPE_FOLDER_NOT_MEMBER", "指定されたチーム/組織に所属していないため追加できません", Severity.WARN),

    /** フォルダのスコープ種別と要求スコープ種別が一致しない（F15.3 §5.3） */
    SCOPE_FOLDER_TYPE_MISMATCH("SCOPE_FOLDER_TYPE_MISMATCH", "フォルダのスコープ種別が一致しません", Severity.WARN),

    /** 未分類フォルダは改名・削除できない（F15.3 §5.3） */
    SCOPE_FOLDER_DEFAULT_IMMUTABLE("SCOPE_FOLDER_DEFAULT_IMMUTABLE", "「未分類」フォルダは変更できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
