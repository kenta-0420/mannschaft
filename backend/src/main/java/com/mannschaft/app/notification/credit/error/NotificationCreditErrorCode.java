package com.mannschaft.app.notification.credit.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.13 通知プリペイドクレジット機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum NotificationCreditErrorCode implements ErrorCode {

    /** 指定されたクレジットパッケージが見つからない */
    CREDIT_PACKAGE_NOT_FOUND("NC_001", "指定されたクレジットパッケージが見つかりません", Severity.WARN),

    /** クレジット残高が不足しており猶予期間も超過している */
    CREDIT_INSUFFICIENT("NC_002", "通知クレジットの残高が不足しており、猶予期間を超過しています", Severity.WARN),

    /** 指定された購入記録が見つからない */
    PURCHASE_NOT_FOUND("NC_003", "指定された購入記録が見つかりません", Severity.WARN),

    /** Stripe Checkout Session の作成に失敗した */
    CHECKOUT_FAILED("NC_004", "決済セッションの作成に失敗しました", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
