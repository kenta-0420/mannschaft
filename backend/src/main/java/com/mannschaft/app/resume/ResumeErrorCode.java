package com.mannschaft.app.resume;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.10 履歴書・職務経歴書のエラーコード定義。
 *
 * <p>所有者不一致や存在しないリソースはすべて {@link #RESUME_001} を返す
 * （IDOR 対策で 403 ではなく 404）。
 * HttpStatus マッピングは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} にて
 * 個別マッピングされる。
 */
@Getter
@RequiredArgsConstructor
public enum ResumeErrorCode implements ErrorCode {

    /** 履歴書が見つからない（所有者不一致 / 存在しない / 論理削除済み）。 */
    RESUME_001("RESUME_001", "履歴書が見つかりません", Severity.WARN),

    /** タイトルは必須。 */
    RESUME_002("RESUME_002", "タイトルは必須です", Severity.WARN),

    /** 登録件数が上限を超過。 */
    RESUME_003("RESUME_003", "登録件数が上限を超えています", Severity.WARN),

    /** 日付の形式が不正。 */
    RESUME_004("RESUME_004", "日付の形式が不正です", Severity.WARN),

    /** 出力種別または形式が不正。 */
    RESUME_005("RESUME_005", "出力種別または形式が不正です", Severity.WARN),

    /** 証明写真サイズが上限超過。 */
    RESUME_006("RESUME_006", "写真サイズが上限を超えています", Severity.WARN),

    /** 非対応の証明写真形式。 */
    RESUME_007("RESUME_007", "非対応の写真形式です", Severity.WARN),

    /** 出力回数の上限到達（レートリミット）。 */
    RESUME_008("RESUME_008", "出力回数の上限に達しました", Severity.WARN),

    /** 帳票生成失敗（PDF / Excel 生成エラー）。 */
    RESUME_009("RESUME_009", "帳票生成に失敗しました", Severity.ERROR),

    /** 楽観ロック競合（他で更新済み）。 */
    RESUME_010("RESUME_010", "編集が競合しました（他で更新済み）", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
