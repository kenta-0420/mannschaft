package com.mannschaft.app.village;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F17.1 村機能のエラーコード定義（設計書 §10 準拠）。
 *
 * <p>本コードは {@link com.mannschaft.app.common.GlobalExceptionHandler} の
 * {@code ERROR_CODE_STATUS_MAP} に対応する HttpStatus が登録されている前提で動作する。
 * 400 / 404 / 403 / 409 などの個別ステータスを返すコードはマップ登録が必須。</p>
 */
@Getter
@RequiredArgsConstructor
public enum VillageErrorCode implements ErrorCode {

    /** 村が存在しない / 削除済み / 凍結済み（404） */
    VILLAGE_NOT_FOUND("VILLAGE_001", "村が見つかりません", Severity.WARN),

    /** UNLISTED 村に非村人がアクセス（403） */
    VILLAGE_UNLISTED("VILLAGE_002", "この村は非公開です", Severity.WARN),

    /** 村名重複（400） */
    VILLAGE_NAME_TAKEN("VILLAGE_003", "その村名はすでに使われています", Severity.WARN),

    /** スラッグ形式不正（400） */
    VILLAGE_SLUG_INVALID("VILLAGE_004", "スラッグの形式が不正です（3〜40文字の英小文字・数字・ハイフン）", Severity.WARN),

    /** スラッグ重複（400） */
    VILLAGE_SLUG_TAKEN("VILLAGE_005", "そのスラッグはすでに使われています", Severity.WARN),

    /** ガイドライン未同意（400） */
    GUIDELINE_NOT_AGREED("VILLAGE_014", "ガイドラインへの同意が必要です", Severity.WARN),

    /** 楽観的ロック競合（409） */
    VERSION_CONFLICT("VILLAGE_018", "更新が競合しました。最新の情報を取得しなおしてください", Severity.WARN),

    /** 新規アカウント（7日以内）による申請（403） */
    NEW_ACCOUNT_RESTRICTED("VILLAGE_022", "新規アカウントはこの操作を行えません", Severity.WARN),

    /** モデレーション権限なし（403） */
    MODERATION_FORBIDDEN("VILLAGE_024", "この操作を行う権限がありません", Severity.WARN),

    /** 凍結済み村への変更操作（409） */
    VILLAGE_ALREADY_ARCHIVED("VILLAGE_027", "凍結済みの村は変更できません", Severity.WARN),

    /** 村作成申請レートリミット超過（429） */
    CREATION_REQUEST_THROTTLED("VILLAGE_010", "村作成のレート上限に達しました（3件/日）", Severity.WARN),

    /** 村作成の権限なし（運営権限が必要）。汎用 FORBIDDEN として 403 を返す。 */
    VILLAGE_CREATE_FORBIDDEN("VILLAGE_028", "公式村の作成には運営権限が必要です", Severity.WARN),

    /** 村説明文・名称・カテゴリの長さ違反など（400） */
    VILLAGE_FIELD_INVALID("VILLAGE_029", "入力値が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
