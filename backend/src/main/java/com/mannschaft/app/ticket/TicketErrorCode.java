package com.mannschaft.app.ticket;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.5 回数券のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum TicketErrorCode implements ErrorCode {

    /** 商品が見つからない */
    PRODUCT_NOT_FOUND("TICKET_001", "回数券商品が見つかりません", Severity.WARN),

    /** チケットが見つからない */
    BOOK_NOT_FOUND("TICKET_002", "回数券が見つかりません", Severity.WARN),

    /** 消化レコードが見つからない */
    CONSUMPTION_NOT_FOUND("TICKET_003", "消化レコードが見つかりません", Severity.WARN),

    /** 決済レコードが見つからない */
    PAYMENT_NOT_FOUND("TICKET_004", "決済レコードが見つかりません", Severity.WARN),

    /** 商品数上限（1チームあたり30件） */
    PRODUCT_LIMIT_EXCEEDED("TICKET_005", "商品数の上限（30件）に達しています", Severity.WARN),

    /** total_tickets の変更は不可 */
    TOTAL_TICKETS_IMMUTABLE("TICKET_006", "回数（total_tickets）は変更できません。回数を変更する場合は新しい商品を作成してください", Severity.WARN),

    /** チケット残数が0（使い切り） */
    TICKET_EXHAUSTED("TICKET_007", "チケットの残数が0です", Severity.WARN),

    /** チケットが ACTIVE でない */
    TICKET_NOT_ACTIVE("TICKET_008", "チケットが利用可能な状態ではありません", Severity.WARN),

    /** 消化日時が未来 */
    CONSUMED_AT_FUTURE("TICKET_009", "消化日時に未来の日時は指定できません", Severity.WARN),

    /** 消化日時が72時間以上前 */
    CONSUMED_AT_TOO_OLD("TICKET_010", "消化日時は過去72時間以内で指定してください", Severity.WARN),

    /** 既に取消済み */
    ALREADY_VOIDED("TICKET_011", "この消化レコードは既に取消済みです", Severity.WARN),

    /** 取消の72時間制限超過 */
    VOID_TIME_EXCEEDED("TICKET_012", "消化から72時間以上経過しているため取消できません。補填が必要な場合は新規発行（amount=0）をご利用ください", Severity.WARN),

    /** 返金額が支払い額を超過 */
    REFUND_AMOUNT_EXCEEDED("TICKET_013", "返金額が支払い額を超過しています", Severity.WARN),

    /** 部分返金で refund_amount 未指定 */
    PARTIAL_REFUND_AMOUNT_REQUIRED("TICKET_014", "部分返金の場合は返金額を指定してください", Severity.WARN),

    /** 既に返金/キャンセル済み */
    ALREADY_REFUNDED("TICKET_015", "既に返金またはキャンセル済みです", Severity.WARN),

    /** 延長対象が無期限チケット */
    CANNOT_EXTEND_NO_EXPIRY("TICKET_016", "無期限チケットに対して有効期限の延長はできません", Severity.WARN),

    /** 延長先が現在の expires_at 以前 */
    EXTEND_DATE_NOT_FUTURE("TICKET_017", "新しい有効期限は現在の有効期限より後の日時を指定してください", Severity.WARN),

    /** 使い切りチケットの延長 */
    CANNOT_EXTEND_EXHAUSTED("TICKET_018", "残数0のチケットは延長できません", Severity.WARN),

    /** キャンセル済みチケットの延長 */
    CANNOT_EXTEND_CANCELLED("TICKET_019", "キャンセル済みのチケットは延長できません", Severity.WARN),

    /** 商品が販売停止中 */
    PRODUCT_NOT_ACTIVE("TICKET_020", "この商品は現在販売停止中です", Severity.WARN),

    /** オンライン購入不可の商品 */
    PRODUCT_NOT_ONLINE_PURCHASABLE("TICKET_021", "この商品はオンライン購入できません", Severity.WARN),

    /** 未決済のため領収書発行不可 */
    RECEIPT_NOT_AVAILABLE("TICKET_022", "未決済のため領収書を発行できません", Severity.WARN),

    /** QR トークンが無効または期限切れ */
    QR_TOKEN_INVALID("TICKET_023", "QRコードが無効または期限切れです", Severity.WARN),

    /** QR ペイロード形式不正 */
    QR_PAYLOAD_INVALID("TICKET_024", "QRコードの形式が不正です", Severity.WARN),

    /** 一括消化の件数上限超過 */
    BULK_CONSUME_LIMIT_EXCEEDED("TICKET_025", "一括消化は最大5件までです", Severity.WARN),

    /** adjusted_remaining が不正値 */
    INVALID_ADJUSTED_REMAINING("TICKET_026", "返金後の残回数が不正です", Severity.WARN),

    /** Stripe API エラー */
    STRIPE_API_ERROR("TICKET_027", "決済サービスとの通信に失敗しました", Severity.ERROR),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("TICKET_028", "この操作に必要な権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
