package com.mannschaft.app.payment;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.2 支払い管理・アクセス制御のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    /** 支払い項目が見つからない */
    PAYMENT_ITEM_NOT_FOUND("PAYMENT_001", "支払い項目が見つかりません", Severity.WARN),

    /** 支払い記録が見つからない */
    PAYMENT_NOT_FOUND("PAYMENT_002", "支払い記録が見つかりません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("PAYMENT_003", "この操作に必要な権限がありません", Severity.WARN),

    /** 既に支払い済み */
    ALREADY_PAID("PAYMENT_004", "既に有効な支払い記録が存在します", Severity.WARN),

    /** Stripe Price 未設定 */
    STRIPE_PRICE_NOT_SET("PAYMENT_005", "オンライン決済が有効化されていません", Severity.WARN),

    /** Stripe API 通信失敗 */
    STRIPE_API_ERROR("PAYMENT_006", "決済サービスとの通信に失敗しました", Severity.ERROR),

    /** type 変更不可 */
    TYPE_IMMUTABLE("PAYMENT_007", "支払い項目の種別は変更できません", Severity.WARN),

    /** Stripe Price 金額不一致 */
    STRIPE_PRICE_MISMATCH("PAYMENT_008", "Stripe Price の金額または通貨が一致しません", Severity.WARN),

    /** 既に返金済み */
    ALREADY_REFUNDED("PAYMENT_009", "既に返金またはキャンセル済みです", Severity.WARN),

    /** 手動支払いは返金不可 */
    MANUAL_PAYMENT_NOT_REFUNDABLE("PAYMENT_010", "手動支払いは返金できません。取り消しを使用してください", Severity.WARN),

    /** PENDING の返金不可 */
    PENDING_PAYMENT_NOT_REFUNDABLE("PAYMENT_011", "決済未完了の支払いは返金できません", Severity.WARN),

    /** コンテンツ種別が不正 */
    UNSUPPORTED_CONTENT_TYPE("PAYMENT_012", "サポートされていないコンテンツ種別です", Severity.WARN),

    /** スコープ外の支払い項目 */
    PAYMENT_ITEM_SCOPE_MISMATCH("PAYMENT_013", "指定された支払い項目は当該スコープに属していません", Severity.WARN),

    /** DONATION はアクセス制御に設定不可 */
    DONATION_NOT_ALLOWED_FOR_ACCESS("PAYMENT_014", "寄付はアクセス制御に設定できません", Severity.WARN),

    /** コンテンツが見つからない */
    CONTENT_NOT_FOUND("PAYMENT_015", "指定されたコンテンツが見つかりません", Severity.WARN),

    /** 一括記録の上限超過 */
    BULK_LIMIT_EXCEEDED("PAYMENT_016", "一括記録は50件までです", Severity.WARN),

    /** DONATION にはリマインド不可 */
    DONATION_REMIND_NOT_ALLOWED("PAYMENT_017", "寄付にはリマインドを送信できません", Severity.WARN),

    /** レートリミット超過 */
    RATE_LIMIT_EXCEEDED("PAYMENT_018", "送信頻度の上限を超えました。しばらく待ってから再試行してください", Severity.WARN),

    /** Webhook 署名検証失敗 */
    WEBHOOK_SIGNATURE_INVALID("PAYMENT_019", "Webhook 署名の検証に失敗しました", Severity.ERROR),

    /** 手動支払いのみ対象 */
    STRIPE_PAYMENT_ONLY("PAYMENT_020", "この操作は Stripe 決済のみ対象です", Severity.WARN),

    /** 既に解約予約済み */
    ALREADY_CANCEL_SCHEDULED("PAYMENT_021", "既に解約予約済みです", Severity.WARN),

    /** 解約予約されていない */
    NOT_CANCEL_SCHEDULED("PAYMENT_022", "解約予約されていません", Severity.WARN),

    /** サブスクリプション期限切れ */
    SUBSCRIPTION_EXPIRED("PAYMENT_023", "サブスクリプションの期限が切れているため再有効化できません", Severity.WARN),

    /** サブスクリプションが見つからない */
    SUBSCRIPTION_NOT_FOUND("PAYMENT_024", "サブスクリプションが見つかりません", Severity.WARN),

    /** 既にサブスクリプション加入済み */
    ALREADY_SUBSCRIBED("PAYMENT_025", "既にサブスクリプションに加入済みです", Severity.WARN),

    /** 論理削除済みの支払い項目 */
    PAYMENT_ITEM_DELETED("PAYMENT_026", "この支払い項目は削除されています", Severity.WARN),

    /** 対象ユーザーがメンバーでない */
    USER_NOT_MEMBER("PAYMENT_027", "対象ユーザーはこのスコープのメンバーではありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
