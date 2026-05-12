package com.mannschaft.app.succession;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.15 居住者死亡・継承支援のエラーコード定義。
 *
 * <p>S1 第三陣B（入居時誓約 API）で必要な誓約系エラーコードを定義する。
 * SuccessionPreRegistration や Unseal 系のエラーコードは別フェーズで追加する。
 */
@Getter
@RequiredArgsConstructor
public enum SuccessionErrorCode implements ErrorCode {

    /** 居住者台帳エントリが見つからない */
    RESIDENT_REGISTRY_NOT_FOUND("SUCCESSION_001", "居住者台帳が見つかりません", Severity.WARN),

    /** 居室が見つからない */
    DWELLING_UNIT_NOT_FOUND("SUCCESSION_002", "居室が見つかりません", Severity.WARN),

    /** 誓約レコードが見つからない */
    COVENANT_NOT_FOUND("SUCCESSION_003", "誓約レコードが見つかりません", Severity.WARN),

    /** 誓約区分が不正 */
    INVALID_COVENANT_TYPE("SUCCESSION_004", "誓約区分が不正です", Severity.WARN),

    /** 誓約への二重署名は禁止（既に同一区分の有効な誓約がある場合） */
    COVENANT_ALREADY_SIGNED("SUCCESSION_005", "既に署名済みの誓約があります", Severity.WARN),

    /** 同意項目（confirmedItems）が不足（ダークパターン回避の必須チェック未通過） */
    COVENANT_CONFIRMED_ITEMS_INSUFFICIENT("SUCCESSION_006", "必須の同意項目が不足しています", Severity.WARN),

    /** 既に撤回済みの誓約を再撤回しようとした */
    COVENANT_ALREADY_REVOKED("SUCCESSION_007", "既に撤回済みの誓約です", Severity.WARN),

    /** 誓約の操作権限がない（本人 / ADMIN 以外） */
    COVENANT_FORBIDDEN("SUCCESSION_008", "この誓約に対する権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
