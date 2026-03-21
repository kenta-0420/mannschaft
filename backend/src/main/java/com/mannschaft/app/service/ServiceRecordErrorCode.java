package com.mannschaft.app.service;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F07.1 サービス履歴のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ServiceRecordErrorCode implements ErrorCode {

    /** サービス記録が見つからない */
    RECORD_NOT_FOUND("SERVICE_RECORD_001", "サービス記録が見つかりません", Severity.WARN),

    /** カスタムフィールドが見つからない */
    FIELD_NOT_FOUND("SERVICE_RECORD_002", "カスタムフィールドが見つかりません", Severity.WARN),

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("SERVICE_RECORD_003", "テンプレートが見つかりません", Severity.WARN),

    /** 添付ファイルが見つからない */
    ATTACHMENT_NOT_FOUND("SERVICE_RECORD_004", "添付ファイルが見つかりません", Severity.WARN),

    /** リアクションが見つからない */
    REACTION_NOT_FOUND("SERVICE_RECORD_005", "リアクションが見つかりません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("SERVICE_RECORD_006", "この操作に必要な権限がありません", Severity.WARN),

    /** カスタムフィールド上限超過 */
    FIELD_LIMIT_EXCEEDED("SERVICE_RECORD_007", "カスタムフィールドの上限（20件）を超えています", Severity.WARN),

    /** テンプレート上限超過 */
    TEMPLATE_LIMIT_EXCEEDED("SERVICE_RECORD_008", "テンプレートの上限を超えています", Severity.WARN),

    /** 添付ファイル上限超過 */
    ATTACHMENT_LIMIT_EXCEEDED("SERVICE_RECORD_009", "添付ファイルの上限（5件）を超えています", Severity.WARN),

    /** 既に確定済み */
    ALREADY_CONFIRMED("SERVICE_RECORD_010", "この記録は既に確定済みです", Severity.WARN),

    /** 必須フィールド未入力 */
    REQUIRED_FIELD_MISSING("SERVICE_RECORD_011", "必須カスタムフィールドが入力されていません", Severity.WARN),

    /** メンバーがチームに所属していない */
    MEMBER_NOT_IN_TEAM("SERVICE_RECORD_012", "指定されたメンバーはチームに所属していません", Severity.WARN),

    /** ダッシュボード共有が無効 */
    DASHBOARD_NOT_ENABLED("SERVICE_RECORD_013", "ダッシュボード共有が有効ではありません", Severity.WARN),

    /** リアクション機能が無効 */
    REACTION_NOT_ENABLED("SERVICE_RECORD_014", "リアクション機能が有効ではありません", Severity.WARN),

    /** 自分の記録でない */
    NOT_OWN_RECORD("SERVICE_RECORD_015", "自分のサービス記録にのみリアクションできます", Severity.WARN),

    /** ファイルサイズ超過 */
    FILE_SIZE_EXCEEDED("SERVICE_RECORD_016", "ファイルサイズが上限（10MB）を超えています", Severity.WARN),

    /** 許可されていないファイル種別 */
    INVALID_CONTENT_TYPE("SERVICE_RECORD_017", "許可されていないファイル種別です", Severity.WARN),

    /** バリデーションエラー */
    VALIDATION_ERROR("SERVICE_RECORD_018", "入力値が不正です", Severity.WARN),

    /** 一括作成上限超過 */
    BULK_LIMIT_EXCEEDED("SERVICE_RECORD_019", "一括作成の上限（20件）を超えています", Severity.WARN),

    /** 一括作成で一部失敗 */
    BULK_PARTIAL_FAILURE("SERVICE_RECORD_020", "一括作成で一部のレコードが失敗しました", Severity.WARN),

    /** 設定が見つからない */
    SETTINGS_NOT_FOUND("SERVICE_RECORD_021", "サービス記録設定が見つかりません", Severity.WARN),

    /** カスタムフィールド値のバリデーションエラー */
    FIELD_VALUE_INVALID("SERVICE_RECORD_022", "カスタムフィールドの値が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
