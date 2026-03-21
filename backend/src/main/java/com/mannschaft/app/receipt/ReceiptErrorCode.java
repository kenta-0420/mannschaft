package com.mannschaft.app.receipt;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.4 領収書発行のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ReceiptErrorCode implements ErrorCode {

    /** 発行者設定が見つからない */
    ISSUER_SETTINGS_NOT_FOUND("RECEIPT_001", "発行者設定が見つかりません", Severity.WARN),

    /** 領収書が見つからない */
    RECEIPT_NOT_FOUND("RECEIPT_002", "領収書が見つかりません", Severity.WARN),

    /** プリセットが見つからない */
    PRESET_NOT_FOUND("RECEIPT_003", "プリセットが見つかりません", Severity.WARN),

    /** キューアイテムが見つからない */
    QUEUE_ITEM_NOT_FOUND("RECEIPT_004", "キューアイテムが見つかりません", Severity.WARN),

    /** 発行者設定が未登録 */
    ISSUER_SETTINGS_NOT_CONFIGURED("RECEIPT_005", "先に発行者設定を行ってください", Severity.WARN),

    /** インボイス登録番号形式不正 */
    INVALID_INVOICE_REGISTRATION_NUMBER("RECEIPT_006", "登録番号の形式が不正です（T + 13桁の数字）", Severity.WARN),

    /** 適格請求書発行事業者で登録番号未設定 */
    INVOICE_REGISTRATION_NUMBER_REQUIRED("RECEIPT_007", "適格請求書発行事業者の場合、登録番号は必須です", Severity.WARN),

    /** 既に無効化済み */
    ALREADY_VOIDED("RECEIPT_008", "この領収書は既に無効化されています", Severity.WARN),

    /** 未無効化の領収書に対する再発行 */
    NOT_VOIDED("RECEIPT_009", "再発行するには先に領収書を無効化してください", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("RECEIPT_010", "この操作に必要な権限がありません", Severity.WARN),

    /** プリセット上限超過 */
    PRESET_LIMIT_EXCEEDED("RECEIPT_011", "プリセットは最大30件までです", Severity.WARN),

    /** 一括操作の上限超過 */
    BULK_LIMIT_EXCEEDED("RECEIPT_012", "一括操作は最大50件までです", Severity.WARN),

    /** 明細行の合計金額不一致 */
    LINE_ITEMS_AMOUNT_MISMATCH("RECEIPT_013", "明細行の合計金額が領収書の金額と一致しません", Severity.ERROR),

    /** 明細行の上限超過 */
    LINE_ITEMS_LIMIT_EXCEEDED("RECEIPT_014", "明細行は最大10件までです", Severity.WARN),

    /** PDF が未生成 */
    PDF_NOT_READY("RECEIPT_015", "PDF が未生成です。生成完了後に再試行してください", Severity.WARN),

    /** メールアドレス未指定（外部受領者） */
    EMAIL_REQUIRED_FOR_EXTERNAL("RECEIPT_016", "外部受領者の場合はメールアドレスを指定してください", Severity.WARN),

    /** 外部受領者で受領者名未指定 */
    RECIPIENT_NAME_REQUIRED("RECEIPT_017", "受領者名は必須です", Severity.WARN),

    /** DEPUTY_ADMIN は ISSUED で直接発行不可 */
    DEPUTY_ADMIN_DRAFT_ONLY("RECEIPT_018", "副管理者は下書きのみ作成可能です", Severity.WARN),

    /** 下書きではない（承認対象外） */
    NOT_DRAFT("RECEIPT_019", "この領収書は下書きではありません", Severity.WARN),

    /** ロゴ画像アップロードエラー */
    LOGO_UPLOAD_FAILED("RECEIPT_020", "ロゴ画像のアップロードに失敗しました", Severity.ERROR),

    /** ZIP ジョブが見つからない */
    ZIP_JOB_NOT_FOUND("RECEIPT_021", "ZIP ダウンロードジョブが見つかりません", Severity.WARN),

    /** CSV エクスポート上限超過 */
    CSV_EXPORT_LIMIT_EXCEEDED("RECEIPT_022", "CSV エクスポートは最大10,000件までです", Severity.WARN),

    /** PDF 生成失敗 */
    PDF_GENERATION_FAILED("RECEIPT_023", "PDF の生成に失敗しました", Severity.ERROR),

    /** キューアイテムが PENDING ではない */
    QUEUE_NOT_PENDING("RECEIPT_024", "このキューアイテムは承認待ち状態ではありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
