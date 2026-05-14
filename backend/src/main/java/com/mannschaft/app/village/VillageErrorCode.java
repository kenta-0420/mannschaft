package com.mannschaft.app.village;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F17.1 村機能のエラーコード定義。
 *
 * <p>B2（村 CRUD）/ B3（メンバーシップ）/ B4（ニックネーム）の各足軽で必要なコードを
 * 1 つの enum に集約する。番号は陣立て段階で割り当てを調整済み（B4 が定義する分は
 * {@code VILLAGE_004 / VILLAGE_008 / VILLAGE_013} の 3 件）。</p>
 *
 * <p>HttpStatus マッピングは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} で個別指定する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum VillageErrorCode implements ErrorCode {

    // ==================================================================
    // B4: 村ニックネーム管理（全村共通 1 つ）
    // ==================================================================

    /** ニックネームがプラットフォーム全体で既に使われている（先着優先・409 Conflict） */
    NICKNAME_TAKEN("VILLAGE_004", "そのニックネームはすでに使われています", Severity.WARN),

    /** ニックネームの長さ・NG ワード・使用文字違反（422 Unprocessable Entity） */
    NICKNAME_INVALID("VILLAGE_008", "ニックネームが無効です（長さ・禁止語・使用文字を確認してください）", Severity.WARN),

    /** ニックネーム変更が月3回を超過した（429 Too Many Requests） */
    NICKNAME_CHANGE_THROTTLED("VILLAGE_013", "ニックネーム変更は月3回までです", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
