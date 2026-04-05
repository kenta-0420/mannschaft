package com.mannschaft.app.notification;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.3 プッシュ通知のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    /** 通知が見つからない */
    NOTIFICATION_NOT_FOUND("NOTIFICATION_001", "通知が見つかりません", Severity.WARN),

    /** 通知設定が見つからない */
    PREFERENCE_NOT_FOUND("NOTIFICATION_002", "通知設定が見つかりません", Severity.WARN),

    /** 通知種別設定が見つからない */
    TYPE_PREFERENCE_NOT_FOUND("NOTIFICATION_003", "通知種別設定が見つかりません", Severity.WARN),

    /** プッシュ購読が見つからない */
    SUBSCRIPTION_NOT_FOUND("NOTIFICATION_004", "プッシュ購読が見つかりません", Severity.WARN),

    /** プッシュ購読が重複している */
    SUBSCRIPTION_ALREADY_EXISTS("NOTIFICATION_005", "このエンドポイントは既に登録されています", Severity.WARN),

    /** 通知は既に既読 */
    ALREADY_READ("NOTIFICATION_006", "通知は既に既読です", Severity.INFO),

    /** 通知は未読状態 */
    ALREADY_UNREAD("NOTIFICATION_007", "通知は既に未読です", Severity.INFO),

    /** スヌーズ日時が過去 */
    INVALID_SNOOZE_TIME("NOTIFICATION_008", "スヌーズ日時は未来である必要があります", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
