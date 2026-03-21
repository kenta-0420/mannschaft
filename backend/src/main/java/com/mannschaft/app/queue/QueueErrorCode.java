package com.mannschaft.app.queue;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.7 順番待ち管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum QueueErrorCode implements ErrorCode {

    /** カテゴリが見つからない */
    CATEGORY_NOT_FOUND("QUEUE_001", "カテゴリが見つかりません", Severity.WARN),

    /** カウンターが見つからない */
    COUNTER_NOT_FOUND("QUEUE_002", "カウンターが見つかりません", Severity.WARN),

    /** チケットが見つからない */
    TICKET_NOT_FOUND("QUEUE_003", "チケットが見つかりません", Severity.WARN),

    /** キュー上限超過 */
    QUEUE_FULL("QUEUE_004", "待ち行列が上限に達しています", Severity.WARN),

    /** チケット発行上限超過 */
    MAX_ACTIVE_TICKETS_EXCEEDED("QUEUE_005", "同時に保持できるチケット数の上限に達しています", Severity.WARN),

    /** カウンター受付停止中 */
    COUNTER_NOT_ACCEPTING("QUEUE_006", "このカウンターは現在受付を停止しています", Severity.WARN),

    /** 無効なチケット状態遷移 */
    INVALID_TICKET_TRANSITION("QUEUE_007", "現在のチケット状態ではこの操作を実行できません", Severity.WARN),

    /** QRコードが見つからない */
    QR_CODE_NOT_FOUND("QUEUE_008", "QRコードが見つかりません", Severity.WARN),

    /** QRコードが無効 */
    QR_CODE_INACTIVE("QUEUE_009", "QRコードが無効です", Severity.WARN),

    /** 設定が見つからない */
    SETTINGS_NOT_FOUND("QUEUE_010", "順番待ち設定が見つかりません", Severity.WARN),

    /** ゲスト受付不可 */
    GUEST_NOT_ALLOWED("QUEUE_011", "ゲストの受付は許可されていません", Severity.WARN),

    /** カウンター非アクティブ */
    COUNTER_INACTIVE("QUEUE_012", "このカウンターは無効化されています", Severity.WARN),

    /** 営業時間外 */
    OUTSIDE_OPERATING_HOURS("QUEUE_013", "営業時間外です", Severity.WARN),

    /** 統計データが見つからない */
    STATS_NOT_FOUND("QUEUE_014", "統計データが見つかりません", Severity.WARN),

    /** 重複QRトークン */
    DUPLICATE_QR_TOKEN("QUEUE_015", "QRトークンが重複しています", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
