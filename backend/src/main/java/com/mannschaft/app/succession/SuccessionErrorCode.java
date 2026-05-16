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
    COVENANT_FORBIDDEN("SUCCESSION_008", "この誓約に対する権限がありません", Severity.WARN),

    /** 事前登録レコードが見つからない */
    PRE_REGISTRATION_NOT_FOUND("SUCCESSION_009", "事前登録が見つかりません", Severity.WARN),

    /** 封緘解除申請レコードが見つからない */
    UNSEAL_REQUEST_NOT_FOUND("SUCCESSION_010", "封緘解除申請が見つかりません", Severity.WARN),

    /** 事前登録が SEALED 状態でないため解除申請できない */
    PRE_REGISTRATION_NOT_SEALED("SUCCESSION_011", "封緘状態でないため解除申請できません", Severity.WARN),

    /** 申請者と承認者が重複している（三者別人要件違反） */
    APPROVER_CONFLICT("SUCCESSION_012", "申請者と承認者が重複しています", Severity.WARN),

    /** 二次承認前に一次承認が完了していない */
    FIRST_APPROVER_REQUIRED("SUCCESSION_013", "一次承認が完了していません", Severity.WARN),

    /** 開封期間が終了しているか、未開封状態 */
    UNSEAL_EXPIRED_OR_INACTIVE("SUCCESSION_014", "開封期間が終了しているか、未開封状態です", Severity.WARN),

    /** 封緘解除中コンテンツへの閲覧権限がない */
    UNSEAL_ACCESS_DENIED("SUCCESSION_015", "この事前登録への閲覧権限がありません", Severity.WARN),

    // ─── F09.15 S5-A: エスカレーション系エラーコード ─────────────────────────

    /** エスカレーションレコードが見つからない */
    ESCALATION_NOT_FOUND("SUCCESSION_016", "エスカレーションが見つかりません", Severity.WARN),

    /** 既に解決済みのエスカレーションを操作しようとした */
    ESCALATION_ALREADY_RESOLVED("SUCCESSION_017", "既に解決済みのエスカレーションです", Severity.WARN),

    /** 凍結中のエスカレーションは操作できない */
    ESCALATION_FROZEN("SUCCESSION_018", "凍結中のエスカレーションは操作できません", Severity.WARN),

    /** 既に最終ステージ（STAGE_5_LEGAL_PREP）のため昇格できない */
    ESCALATION_ALREADY_FINAL_STAGE("SUCCESSION_019", "既に最終ステージです", Severity.WARN),

    /** 同一居住者に未解決のエスカレーションが既に存在する */
    ESCALATION_DUPLICATE("SUCCESSION_020", "重複するエスカレーションが既に存在します", Severity.WARN),

    // ─── SUCCESSION_021〜022: 法的手続き（LegalFiling 系）─────────────────────────

    /** 法的手続きレコードが見つからない */
    LEGAL_FILING_NOT_FOUND("SUCCESSION_021", "法的手続きレコードが見つかりません", Severity.WARN),

    /** 証拠パッケージがまだ生成されていない */
    EVIDENCE_NOT_READY("SUCCESSION_022", "証拠パッケージがまだ生成されていません。先に buildEvidencePackage を実行してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
