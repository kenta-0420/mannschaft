package com.mannschaft.app.directmail;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.6 ダイレクトメール配信のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum DirectMailErrorCode implements ErrorCode {

    /** メールが見つからない */
    MAIL_NOT_FOUND("DM_001", "ダイレクトメールが見つかりません", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("DM_002", "テンプレートが見つかりません", Severity.WARN),

    /** 下書き以外は編集不可 */
    NOT_DRAFT("DM_003", "下書き状態のメールのみ編集できます", Severity.WARN),

    /** 送信中はキャンセル不可 */
    CANNOT_CANCEL("DM_004", "送信中のメールはキャンセルできません", Severity.WARN),

    /** 既に送信済み */
    ALREADY_SENT("DM_005", "このメールは既に送信済みです", Severity.WARN),

    /** SES 送信エラー */
    SES_SEND_ERROR("DM_006", "メール送信に失敗しました", Severity.ERROR),

    /** 画像アップロードエラー */
    IMAGE_UPLOAD_ERROR("DM_007", "画像のアップロードに失敗しました", Severity.ERROR),

    /** 画像サイズ超過 */
    IMAGE_SIZE_EXCEEDED("DM_008", "画像サイズの上限（5MB）を超えています", Severity.WARN),

    /** 許可されていないファイル形式 */
    INVALID_IMAGE_TYPE("DM_009", "許可されていない画像形式です", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("DM_010", "この操作に必要な権限がありません", Severity.WARN),

    /** 予約日時が過去 */
    SCHEDULE_IN_PAST("DM_011", "予約日時は未来の日時を指定してください", Severity.WARN),

    /** SES 通知のペイロード不正 */
    INVALID_SES_NOTIFICATION("DM_012", "SES通知の形式が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
