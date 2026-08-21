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

    /**
     * 誓約の操作権限がない（本人 / ADMIN 以外）（404）。
     *
     * <p><b>これは「意味が割れている」のではなく意図的な集約である。分割してはならない。</b>
     * 本コードは (1) 他人が署名した誓約 ID への越境アクセス と (2) 本人でも組織 ADMIN でもない者による権限拒否 の両方に使われる。この2つを別コード・別ステータスに分けると、応答の差から
     * 「そのIDのリソースは実在する」ことを外部から判定できる存在オラクルになる。</p>
     *
     * <p><b>ステータスは404固定。</b>不在（{@link #COVENANT_NOT_FOUND}）と同一の404に畳むことで秘匿を達成する。
     * このコードベースには PARKING_020 を起点とする「越境は存在秘匿で404」の流儀が確立しており
     * （equipment/membership/todo/corkboard/pointcard/skill で実装済み）、それに揃えた。
     * かつては 403 を返しており、不在（404）と越境（403）でステータスが割れて存在オラクルになっていた。
     * 「403に戻すべきでは」と迷った場合は、この理由を思い出すこと。
     * （{@code GlobalExceptionHandlerTest.ExistenceOracleParity} が
     * 「不在と越境の応答が一致すること」を契約として固定している）。</p>
     */
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
    EVIDENCE_NOT_READY("SUCCESSION_022", "証拠パッケージがまだ生成されていません。先に buildEvidencePackage を実行してください", Severity.WARN),

    /**
     * 誓約一覧取得の権限がない（403）。
     *
     * <p>これは {@link #COVENANT_FORBIDDEN} とは別物である。組織スコープの一覧取得であり、
     * 秘匿すべき個別の誓約 ID を一切引かない汎用の権限拒否であるため、
     * ID 越境の 404 化（存在秘匿）の対象にはならない。したがってステータスは 403 のまま据え置く。</p>
     */
    COVENANT_LIST_FORBIDDEN("SUCCESSION_023", "誓約一覧を取得する権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
