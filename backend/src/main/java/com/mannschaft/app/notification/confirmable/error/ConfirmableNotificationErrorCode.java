package com.mannschaft.app.notification.confirmable.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.9 確認通知システムのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ConfirmableNotificationErrorCode implements ErrorCode {

    /** 確認通知が見つからない */
    NOT_FOUND("CONFIRMABLE_NOTIFICATION_NOT_FOUND", "確認通知が見つかりません", Severity.WARN),

    /** 受信者が見つからない */
    RECIPIENT_NOT_FOUND("CONFIRMABLE_NOTIFICATION_RECIPIENT_NOT_FOUND", "受信者が見つかりません", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND", "テンプレートが見つかりません", Severity.WARN),

    /** この通知はすでにキャンセルされている */
    ALREADY_CANCELLED("CONFIRMABLE_NOTIFICATION_ALREADY_CANCELLED", "この通知はすでにキャンセルされています", Severity.WARN),

    /** すでに確認済み */
    ALREADY_CONFIRMED("CONFIRMABLE_NOTIFICATION_ALREADY_CONFIRMED", "すでに確認済みです", Severity.WARN),

    /** 確認トークンが無効 */
    INVALID_TOKEN("CONFIRMABLE_NOTIFICATION_INVALID_TOKEN", "確認トークンが無効です", Severity.WARN),

    /** スコープが一致しない */
    SCOPE_MISMATCH("CONFIRMABLE_NOTIFICATION_SCOPE_MISMATCH", "スコープが一致しません", Severity.WARN),

    /** 確認通知の送信に失敗した */
    SEND_FAILED("CONFIRMABLE_NOTIFICATION_SEND_FAILED", "確認通知の送信に失敗しました", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
