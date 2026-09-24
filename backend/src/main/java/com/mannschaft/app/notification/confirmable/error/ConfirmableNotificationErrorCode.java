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

    /**
     * 確認通知の送信に失敗した。
     *
     * <p>throw元（ConfirmableNotificationService）は「受信者リストが空」「受信者数が上限を超過」
     * という2箇所のみで、いずれも外部送信の失敗ではなく純粋なクライアント入力検証である。
     * 命名から「外部サービス障害」と誤認して Severity.ERROR（既定500）としていたが、
     * 全数調査で誤分類と判明したため WARN（既定400）に是正する。</p>
     */
    SEND_FAILED("CONFIRMABLE_NOTIFICATION_SEND_FAILED", "確認通知の送信に失敗しました", Severity.WARN),

    // -------------------------------------------------------------------------
    // CMP-260920-1040: 確認通知「宛先指定」（軍議第8版確定稿 §3.3・§4）
    // -------------------------------------------------------------------------

    /** targets=[] （何も選んでいない）。AC-9: 400 */
    TARGETS_EMPTY("CONFIRMABLE_NOTIFICATION_TARGETS_EMPTY", "宛先が選択されていません", Severity.WARN),

    /** targets と recipientGroupId の両方を指定。AC-10: 400 */
    TARGETS_AND_GROUP_BOTH_SPECIFIED(
            "CONFIRMABLE_NOTIFICATION_TARGETS_AND_GROUP_BOTH_SPECIFIED",
            "宛先はtargetsとrecipientGroupIdのどちらか一方のみ指定してください", Severity.WARN),

    /** 指定したターゲットが送信スコープの配下にない。AC-12〜14: 403 */
    TARGET_OUT_OF_SCOPE("CONFIRMABLE_NOTIFICATION_TARGET_OUT_OF_SCOPE", "指定した宛先は送信範囲の配下にありません", Severity.WARN),

    /** 指定した宛先グループが送信スコープに存在しない（削除済み含む）。AC-15: 404（存在秘匿） */
    RECIPIENT_GROUP_NOT_FOUND("CONFIRMABLE_NOTIFICATION_RECIPIENT_GROUP_NOT_FOUND", "宛先グループが見つかりません", Severity.WARN),

    /** 見込み受信者が0件。AC-20: 409 */
    RECIPIENTS_EMPTY("CONFIRMABLE_NOTIFICATION_RECIPIENTS_EMPTY", "宛先に該当する受信者がいません", Severity.WARN),

    /** 同じスコープに同名の宛先グループが既に存在する。AC-31: 409 */
    GROUP_NAME_DUPLICATE("CONFIRMABLE_NOTIFICATION_GROUP_NAME_DUPLICATE", "同名の宛先グループが既に存在します", Severity.WARN),

    /** 組織の通知クレジットが猶予超過。AC-26 等: 402想定（httpStatusOverride で明示） */
    CREDIT_INSUFFICIENT("CONFIRMABLE_NOTIFICATION_CREDIT_INSUFFICIENT", "通知クレジットが不足しています", Severity.WARN),

    /** 確認期限（deadlineAt）が受け付けの時点で既に過去。AC-52: 400 */
    DEADLINE_IN_PAST("CONFIRMABLE_NOTIFICATION_DEADLINE_IN_PAST", "確認期限は現在時刻より後を指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
