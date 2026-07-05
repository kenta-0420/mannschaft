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
    INVALID_DISPLAY_ORDER("RESERVATION_025", "表示順は1〜5の範囲で指定してください", Severity.WARN),

    /**
     * キャンセル締切超過（会員キャンセル拒否・入力不正なので 400）。
     *
     * <p>F03.4 §3 「{@code reservation_policies.cancel_deadline_hours}（既定 24）を実適用し、
     * 枠開始時刻の {@code deadline} 時間前を過ぎた会員（USER）キャンセルは拒否する」を
     * Service 層（{@code ReservationService.cancelByUser}）で担保する（段階拡張バックログ ⑤）。
     * 判定は注入 {@code Clock} 基準。管理者（ADMIN）キャンセルは締切の対象外（常時キャンセル可）。
     * Severity.WARN のため {@code GlobalExceptionHandler} の既定マッピングで 400 になる（個別 map 不要）。</p>
     */
    CANCEL_DEADLINE_PASSED("RESERVATION_026", "キャンセル締切を過ぎているためキャンセルできません", Severity.WARN),

    /**
     * 予約不可枠（機能B）の登録/更新時、対象枠と時間帯 overlap する active 予約
     * （{@code PENDING} / {@code CONFIRMED}）が既に存在する（リソース競合・409）。
     *
     * <p>F03.4 §3.B/§5.B「予約不可枠 作成/更新の 409 ガード」を担保する。
     * 既存予約は強制キャンセルせず、管理者が impact API（{@code .../blocked-times/impact}）で
     * 確認 → 振替/キャンセルしてから登録する運用の最終防御。
     * Severity.WARN だが {@code GlobalExceptionHandler} の個別マッピングで HTTP 409 に上書きする。</p>
     */
    UNAVAILABILITY_HAS_ACTIVE_RESERVATIONS("RESERVATION_027",
            "この予約不可枠と重複する有効な予約が存在します。先に振替またはキャンセルしてください", Severity.WARN),

    // ===== 機能D: 予約通知メール宛先（フリーミアム件数ゲート）=====

    /**
     * 予約通知メール宛先が上限（10 件）に到達（入力上限超過なので 400）。
     *
     * <p>F03.4 §4.D/§5.D「{@code count >= MAX_RECIPIENT_LIMIT(10)}」を Service 層
     * （{@code ReservationNotificationRecipientService.addRecipient}）で担保する。
     * 有料でも 10 件超は不可。Severity.WARN のため {@code GlobalExceptionHandler} の
     * 既定マッピングで 400 になる（個別 map 不要）。</p>
     */
    NOTIFY_RECIPIENT_LIMIT_EXCEEDED("RESERVATION_028", "予約通知メール宛先はチームあたり最大10件までです", Severity.WARN),

    /**
     * 無料プランで 4 件目以降の宛先を追加（有料プラン必須なので 402 Payment Required）。
     *
     * <p>F03.4 §4.D/§5.D「{@code count >= FREE_RECIPIENT_LIMIT(3)} かつ {@code !hasPaidPlan}」を
     * {@code TeamPlanService.hasPaidPlan} で判定して担保する。HTTP は 402（Payment Required＝
     * 有料課金で解放される意味論）。{@code GlobalExceptionHandler} の個別マッピングで 402 に上書きする。</p>
     */
    NOTIFY_RECIPIENT_PAID_PLAN_REQUIRED("RESERVATION_029", "無料プランでは予約通知メール宛先は3件までです。4件目以降は有料プランが必要です", Severity.WARN),

    /**
     * 同一チームで email 重複（リソース競合なので 409 Conflict）。
     *
     * <p>F03.4 §4.D「同一チームで {@code email} 重複」を Service 層で事前に 409 として弾く。
     * DB {@code UNIQUE(team_id, email)} が最終防御。{@code GlobalExceptionHandler} の
     * 個別マッピングで 409 に上書きする。</p>
     */
    NOTIFY_RECIPIENT_DUPLICATE("RESERVATION_030", "この宛先メールアドレスは既に登録されています", Severity.WARN),

    /**
     * 予約通知メール宛先が見つからない（PATCH/DELETE 対象不在・404）。
     *
     * <p>{@code findByIdAndTeamId} で解決できない場合に throw する（他チームの宛先を掴んだ場合も
     * IDOR 対策として同一の 404 で隠蔽する）。{@code GlobalExceptionHandler} の個別マッピングで 404。</p>
     */
    NOTIFY_RECIPIENT_NOT_FOUND("RESERVATION_031", "予約通知メール宛先が見つかりません", Severity.WARN),

    // ===== F03.4.1 機能E: 予約メニュー（v2 第一弾）=====

    /**
     * メニュー不存在（PATCH/DELETE 対象不在・404）。
     *
     * <p>F03.4.1 §4/§6: {@code findByIdAndTeamId} で解決できない場合に throw する。
     * 他チームのメニュー ID を掴んだ場合も IDOR 対策として同一の 404 で隠蔽する。
     * {@code GlobalExceptionHandler} の個別マッピングで 404。</p>
     */
    MENU_NOT_FOUND("RESERVATION_032", "メニューが見つかりません", Severity.WARN),

    /**
     * メニュー上限（1 チームあたり 20 件）超過（入力上限超過なので 400）。
     *
     * <p>F03.4.1 §3: 論理削除済みは数えない（有効・無効は問わず数える）。
     * Service 層（{@code ReservationMenuService.createMenu}）で担保する。</p>
     */
    MENU_LIMIT_EXCEEDED("RESERVATION_033", "メニューはチームあたり最大20件までです", Severity.WARN),

    /**
     * 所要時間が 30 の倍数でない / 30〜480 範囲外（入力不正なので 400）。
     *
     * <p>F03.4.1 §3: {@code duration_minutes} は 30 の倍数・30〜480。Service 層が一次検証、
     * DB の CHECK 制約（MySQL 8.0.16+ 実 enforce）が最終防御。</p>
     */
    INVALID_MENU_DURATION("RESERVATION_034", "所要時間は30分単位（30〜480分）で指定してください", Severity.WARN),

    /**
     * <b>メニュー定義時（POST/PATCH）</b>の {@code lineIds} 不正（入力不正なので 400）。
     *
     * <p>F03.4.1 §4/§9: 不正 ID / 他チームのライン / 削除済みラインを含む場合。
     * 他チームのライン ID も同コード（存在秘匿）。予約時の「提供不可ラインでの確保」は
     * 別コード RESERVATION_043（F03.4.3 で採番）— 意味衝突の回避。</p>
     */
    MENU_LINE_IDS_INVALID("RESERVATION_035", "選択した予約対象が無効です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
