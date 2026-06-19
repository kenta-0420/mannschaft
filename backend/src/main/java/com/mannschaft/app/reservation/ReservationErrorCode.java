package com.mannschaft.app.reservation;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.4 予約管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ReservationErrorCode implements ErrorCode {

    /** 予約ラインが見つからない */
    LINE_NOT_FOUND("RESERVATION_001", "予約ラインが見つかりません", Severity.WARN),

    /** 予約スロットが見つからない */
    SLOT_NOT_FOUND("RESERVATION_002", "予約スロットが見つかりません", Severity.WARN),

    /** 予約が見つからない */
    RESERVATION_NOT_FOUND("RESERVATION_003", "予約が見つかりません", Severity.WARN),

    /** スロットが満席 */
    SLOT_FULL("RESERVATION_004", "このスロットは満席です", Severity.WARN),

    /** スロットがクローズ済み */
    SLOT_CLOSED("RESERVATION_005", "このスロットは受付終了しています", Severity.WARN),

    /** 予約ステータス不正 */
    INVALID_RESERVATION_STATUS("RESERVATION_006", "この操作は現在の予約ステータスでは実行できません", Severity.WARN),

    /** 開始時刻と終了時刻の整合性エラー（入力不正なので 400） */
    INVALID_TIME_RANGE("RESERVATION_007", "開始時刻は終了時刻より前である必要があります", Severity.WARN),

    /** 営業時間外 */
    OUTSIDE_BUSINESS_HOURS("RESERVATION_008", "営業時間外の時刻が指定されています", Severity.WARN),

    /** ブロック時間帯 */
    BLOCKED_TIME_CONFLICT("RESERVATION_009", "ブロックされた時間帯と重複しています", Severity.WARN),

    /** 営業時間が見つからない */
    BUSINESS_HOURS_NOT_FOUND("RESERVATION_010", "営業時間設定が見つかりません", Severity.WARN),

    /** ブロック時間が見つからない */
    BLOCKED_TIME_NOT_FOUND("RESERVATION_011", "ブロック時間が見つかりません", Severity.WARN),

    /** リマインダーが見つからない */
    REMINDER_NOT_FOUND("RESERVATION_012", "リマインダーが見つかりません", Severity.WARN),

    /** 予約重複 */
    DUPLICATE_RESERVATION("RESERVATION_013", "同じスロットに既に予約が存在します", Severity.WARN),

    /** 過去日付への予約 */
    PAST_DATE_RESERVATION("RESERVATION_014", "過去の日付には予約できません", Severity.WARN),

    /** リマインダー上限超過 */
    MAX_REMINDERS_EXCEEDED("RESERVATION_015", "リマインダーは最大3件です", Severity.ERROR),

    /** 臨時休業が見つからない */
    CLOSURE_NOT_FOUND("RESERVATION_016", "臨時休業が見つかりません", Severity.WARN),

    /** 臨時休業確認レコードが見つからない */
    CLOSURE_CONFIRMATION_NOT_FOUND("RESERVATION_017", "臨時休業確認レコードが見つかりません", Severity.WARN),

    /** 臨時休業の日付範囲が不正（入力不正なので 400） */
    INVALID_CLOSURE_DATE_RANGE("RESERVATION_018", "終了日は開始日以降である必要があります", Severity.WARN),

    /** 臨時休業の時刻範囲が不正（入力不正なので 400） */
    INVALID_CLOSURE_TIME_RANGE("RESERVATION_019", "時刻範囲が不正です。開始・終了は両方指定し、整時（HH:00）かつ開始 < 終了である必要があります", Severity.WARN),

    /** 予約入り枠の削除拒否（active な予約が紐づくスロットは削除不可・409） */
    SLOT_HAS_ACTIVE_RESERVATIONS("RESERVATION_020", "このスロットには有効な予約が存在するため削除できません", Severity.WARN),

    /**
     * 予約認可ゲート: チーム所属者でない者が一般公開OFFのチームに予約しようとした。
     *
     * <p>既定（allow_public_reservation=false）はチーム所属（SUPPORTER 以上＝memberships 存在）を要求する。
     * 裏設定で公開（true）にした場合はログイン済みであれば誰でも予約可（匿名は認証層で 401）。
     * Severity.WARN だが {@code GlobalExceptionHandler} の個別マッピングで HTTP 403 に上書きする。</p>
     */
    RESERVATION_PERMISSION_DENIED("RESERVATION_021", "このチームに予約する権限がありません", Severity.WARN),

    /**
     * 枠の時刻が 30 分グリッドに乗っていない、または枠長が 30 分未満（入力不正なので 400）。
     *
     * <p>F03.4 §3 「{@code start_time}/{@code end_time} の分は {@code 00} または {@code 30} のみ。最小枠 30 分」を
     * Service 層（{@code ReservationSlotService.validateTimeRange}）で担保する（段階拡張バックログ ②）。</p>
     */
    INVALID_SLOT_GRANULARITY("RESERVATION_022", "予約枠は30分単位で、最小30分以上である必要があります", Severity.WARN),

    /**
     * 過去日付の枠作成（入力不正なので 400）。
     *
     * <p>F03.4 §3 「{@code slot_date} は当日以降のみ作成可能。過去日は 400」を Service 層で担保する
     * （段階拡張バックログ ③）。予約（reservation）用の {@link #PAST_DATE_RESERVATION} とは
     * 文脈が異なる（こちらは ADMIN による枠定義）ため別コードを割り当てる。判定は注入 {@code Clock} 基準。</p>
     */
    PAST_DATE_SLOT("RESERVATION_023", "過去の日付には予約枠を作成できません", Severity.WARN),

    /**
     * 予約ライン数の上限（5 本）超過（入力不正なので 400）。
     *
     * <p>F03.4 §1/§2 「1 チームあたり最大 5 本の予約ライン」を Service 層
     * （{@code ReservationLineService.createLine}）で担保する（段階拡張バックログ ④）。</p>
     */
    LINE_LIMIT_EXCEEDED("RESERVATION_024", "予約ラインはチームあたり最大5本までです", Severity.WARN),

    /**
     * 予約ラインの表示順（display_order）がチーム内許可範囲（1〜5）外（入力不正なので 400）。
     *
     * <p>F03.4 §2 「{@code display_order} はチーム内で 1〜5 の範囲。Service 層で保証」を担保する
     * （段階拡張バックログ ④ の付随検証）。</p>
     */
    INVALID_DISPLAY_ORDER("RESERVATION_025", "表示順は1〜5の範囲で指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
