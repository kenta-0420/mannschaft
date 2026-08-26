package com.mannschaft.app.schedule;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.1 スケジュール・出欠管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleErrorCode implements ErrorCode {

    /** スケジュールが見つからない → 404（越境時は別途スコープ認可チェックが 403 を返す） */
    SCHEDULE_NOT_FOUND("SCHEDULE_001", "スケジュールが見つかりません", Severity.WARN),

    /** 開始日時と終了日時の整合性エラー */
    INVALID_DATE_RANGE("SCHEDULE_002", "開始日時は終了日時より前である必要があります", Severity.WARN),

    /** 出欠管理対象外のスケジュール */
    ATTENDANCE_NOT_REQUIRED("SCHEDULE_003", "このスケジュールは出欠管理対象外です", Severity.WARN),

    /** 出欠回答期限超過（409: 期限経過という状態競合） */
    ATTENDANCE_DEADLINE_PASSED("SCHEDULE_004", "出欠回答期限を過ぎています", Severity.WARN),

    /** 既にキャンセル済み（状態遷移違反 → 409） */
    SCHEDULE_ALREADY_CANCELLED("SCHEDULE_005", "スケジュールは既にキャンセルされています", Severity.WARN),

    /** 既に完了済み */
    SCHEDULE_ALREADY_COMPLETED("SCHEDULE_006", "スケジュールは既に完了しています", Severity.WARN),

    /** アンケート設問数上限超過（409: 件数上限という状態競合） */
    MAX_SURVEYS_EXCEEDED("SCHEDULE_007", "アンケート設問は最大10件です", Severity.WARN),

    /**
     * リマインダー数上限超過。兄弟の {@code ReservationErrorCode.MAX_REMINDERS_EXCEEDED}
     * （RESERVATION_015・enum 定数名まで同一の概念）が既定 400 のままであるため、系統を割らないよう
     * 本コードも Severity.WARN 既定の 400 のままとする（
     * {@code GlobalExceptionHandlerTest#リマインダー件数上限は両ドメインで同一ステータス} が固定）。
     */
    MAX_REMINDERS_EXCEEDED("SCHEDULE_008", "リマインダーは最大5件です", Severity.WARN),

    /** 同一招待先への重複招待（状態遷移違反 → 409） */
    CROSS_INVITE_ALREADY_EXISTS("SCHEDULE_009", "同じ招待先への招待が既に存在します", Severity.WARN),

    /** 招待が見つからない → 404 */
    CROSS_INVITE_NOT_FOUND("SCHEDULE_010", "招待が見つかりません", Severity.WARN),

    /** 招待状態不正（409: 状態遷移違反） */
    CROSS_INVITE_INVALID_STATUS("SCHEDULE_011", "この操作は現在の招待状態では実行できません", Severity.WARN),

    /** 繰り返しルール不正 */
    INVALID_RECURRENCE_RULE("SCHEDULE_012", "繰り返しルールが不正です", Severity.WARN),

    /** アンケート設問が見つからない → 404 */
    SURVEY_NOT_FOUND("SCHEDULE_013", "アンケート設問が見つかりません", Severity.WARN),

    /** コメント必須エラー */
    COMMENT_REQUIRED("SCHEDULE_014", "コメントは必須です", Severity.WARN),

    /** スコープ不正 */
    INVALID_SCOPE("SCHEDULE_015", "スケジュールのスコープが不正です", Severity.WARN),

    /** アクセス権なし */
    ACCESS_DENIED("SCHEDULE_016", "このスケジュールへのアクセス権がありません", Severity.WARN),

    /**
     * 二軸（配信 × 閲覧）の不変条件違反（400・CMP-017b）。
     *
     * <p>{@code include_supporters = TRUE}（応援者にも出欠を配る）でありながら
     * {@code min_view_role} が {@code MEMBER_PLUS} / {@code ADMIN_ONLY}（応援者は閲覧不可）である
     * 組み合わせは「応援者に出欠を配るが応援者は予定を見られない」自己矛盾であり、
     * 書込時に拒否する。</p>
     */
    INCONSISTENT_SUPPORTER_AXES(
            "SCHEDULE_017",
            "応援者を出欠配信対象に含める設定と、応援者が閲覧できない最小閲覧ロールは同時に指定できません",
            Severity.WARN),

    /** 個人リマインダー上限超過（409: 件数上限という状態競合） */
    PERSONAL_REMINDER_LIMIT_EXCEEDED("SCHEDULE_019", "個人スケジュールのリマインダーは最大3件です", Severity.WARN),

    /** 個人スケジュール上限超過（409: 件数上限という状態競合） */
    PERSONAL_SCHEDULE_LIMIT_EXCEEDED("SCHEDULE_020", "個人スケジュールの上限（1000件）に達しています", Severity.WARN),

    /** 一括削除上限超過 */
    BATCH_DELETE_LIMIT_EXCEEDED("SCHEDULE_021", "一括削除は最大50件までです", Severity.WARN),

    /** スケジュール所有者不一致（存在は隠さず本人以外を拒否 → 403） */
    NOT_SCHEDULE_OWNER("SCHEDULE_022", "このスケジュールの所有者ではありません", Severity.WARN),

    /** Google Calendar未連携 */
    GOOGLE_CALENDAR_NOT_CONNECTED("SCHEDULE_030", "Google Calendarが連携されていません", Severity.WARN),

    /** Google Calendar連携済み */
    GOOGLE_CALENDAR_ALREADY_CONNECTED("SCHEDULE_031", "Google Calendarは既に連携されています", Severity.WARN),

    /** Google Calendar認証エラー */
    GOOGLE_CALENDAR_AUTH_ERROR("SCHEDULE_032", "Google Calendar認証エラー", Severity.ERROR),

    /** Google Calendar同期失敗 */
    GOOGLE_CALENDAR_SYNC_FAILED("SCHEDULE_033", "Google Calendar同期に失敗しました", Severity.ERROR),

    /** iCalトークン不在 */
    ICAL_TOKEN_NOT_FOUND("SCHEDULE_040", "iCalトークンが見つかりません", Severity.WARN),

    /** iCalトークン無効 */
    ICAL_TOKEN_INACTIVE("SCHEDULE_041", "iCalトークンが無効です", Severity.WARN),

    /** iCalレート制限 */
    ICAL_RATE_LIMITED("SCHEDULE_042", "リクエスト頻度が高すぎます", Severity.WARN),

    /** OAuthステート不一致 */
    OAUTH_STATE_MISMATCH("SCHEDULE_043", "CSRF検証に失敗しました", Severity.ERROR),

    /** OAuthトークン取得失敗 */
    OAUTH_TOKEN_EXCHANGE_FAILED("SCHEDULE_044", "OAuthトークン取得に失敗しました", Severity.ERROR),

    /** 連携TODOとスケジュールのスコープが一致しない */
    TODO_SCOPE_MISMATCH("SCHEDULE_050", "連携TODOとスケジュールのスコープが一致しません", Severity.WARN),

    /** このTODOは既に別のスケジュールと連携されている（TODO_032/033 と同型・状態遷移違反 → 409） */
    TODO_ALREADY_LINKED("SCHEDULE_051", "このTODOは既に別のスケジュールと連携されています", Severity.WARN),

    // --- F03.12 スケジュールメディア ---

    /** メディアが見つからない */
    SCHEDULE_MEDIA_NOT_FOUND("SCHEDULE_060", "メディアが見つかりません", Severity.WARN),

    /** アップロード権限なし */
    SCHEDULE_MEDIA_UPLOAD_FORBIDDEN("SCHEDULE_061", "メディアのアップロード権限がありません", Severity.WARN),

    /** メディア操作権限なし */
    SCHEDULE_MEDIA_OPERATION_FORBIDDEN("SCHEDULE_062", "このメディアを操作する権限がありません", Severity.WARN),

    /** 画像上限超過 */
    SCHEDULE_MEDIA_IMAGE_LIMIT_EXCEEDED("SCHEDULE_063", "1スケジュールあたりの画像上限（50枚）を超えています", Severity.WARN),

    /** 動画上限超過 */
    SCHEDULE_MEDIA_VIDEO_LIMIT_EXCEEDED("SCHEDULE_064", "1スケジュールあたりの動画上限（5本）を超えています", Severity.WARN),

    /** サポート外の MIME タイプ */
    SCHEDULE_MEDIA_UNSUPPORTED_TYPE("SCHEDULE_065", "サポートされていないファイル形式です", Severity.WARN),

    /** ファイルサイズ超過 */
    SCHEDULE_MEDIA_SIZE_EXCEEDED("SCHEDULE_066", "ファイルサイズが上限を超えています", Severity.WARN),

    /** is_cover 変更権限なし（MEMBER は変更不可） */
    SCHEDULE_MEDIA_COVER_FORBIDDEN("SCHEDULE_067", "カバー写真の設定はADMIN/DEPUTY_ADMINのみ可能です", Severity.WARN),

    // --- F03.10 代理出席（§4.1 / §5.6） ---

    /** 代理委任が見つからない（404） */
    SCHEDULE_DELEGATION_NOT_FOUND("SCHEDULE_070", "代理委任が見つかりません", Severity.WARN),

    /** 委任者がスコープのメンバーでない（403） */
    SCHEDULE_DELEGATION_DELEGATOR_NOT_MEMBER("SCHEDULE_071", "委任者はスコープのメンバーではありません", Severity.WARN),

    /** 代理人がスコープのメンバーでない（422） */
    SCHEDULE_DELEGATION_DELEGATE_NOT_MEMBER("SCHEDULE_072", "代理人はスコープのメンバーではありません", Severity.WARN),

    /** 自己代理（422） */
    SCHEDULE_DELEGATION_SELF_DELEGATION("SCHEDULE_073", "自分自身を代理人に指定することはできません", Severity.WARN),

    /** 委任者のアクティブ代理が既に存在する（409） */
    SCHEDULE_DELEGATION_ALREADY_EXISTS("SCHEDULE_074", "この委任者のアクティブな代理が既に存在します", Severity.WARN),

    /** 連鎖代理禁止違反（422） */
    SCHEDULE_DELEGATION_CHAINED("SCHEDULE_075", "代理人が既に他者の代理を引き受けているため指定できません（連鎖代理禁止）", Severity.WARN),

    /** 代理出席が許可されていない（422） */
    SCHEDULE_DELEGATION_NOT_ALLOWED("SCHEDULE_076", "このスケジュールは代理出席を許可していません", Severity.WARN),

    /** スケジュールが CANCELLED/COMPLETED（422） */
    SCHEDULE_DELEGATION_INVALID_SCHEDULE_STATUS("SCHEDULE_077", "キャンセル済み・完了済みのスケジュールには代理指定できません", Severity.WARN),

    /** 親（繰り返し）スケジュールへの指定（422） */
    SCHEDULE_DELEGATION_PARENT_SCHEDULE("SCHEDULE_078", "繰り返しの親スケジュールには代理指定できません。各回に個別指定してください", Severity.WARN),

    /** 代理人本人でない（403） */
    SCHEDULE_DELEGATION_NOT_DELEGATE("SCHEDULE_079", "代理人本人のみ承認・拒否できます", Severity.WARN),

    /** ステータスが PENDING でない（422） */
    SCHEDULE_DELEGATION_NOT_PENDING("SCHEDULE_080", "承認待ち（PENDING）状態の代理のみ承認・拒否できます", Severity.WARN),

    // --- 機能55 予約作成（第二陣） ---

    /** 予約タスクの payload 直列化に失敗（500） */
    SCHEDULED_TASK_PAYLOAD_SERIALIZATION_FAILED(
            "SCHEDULE_090", "予約タスクの保存に失敗しました", Severity.ERROR),

    // --- 機能55 予約作成（第三陣） ---

    /** 予約タスクが見つからない（404） */
    SCHEDULED_TASK_NOT_FOUND("SCHEDULE_091", "予約タスクが見つかりません", Severity.WARN),

    /** PENDING 以外の予約タスクは取り消せない（409） */
    SCHEDULED_TASK_NOT_CANCELLABLE(
            "SCHEDULE_092", "この予約タスクは取り消せません（作成待ち状態ではありません）", Severity.WARN),

    /** 予定対象者のモード・件数・閲覧ロール条件が不正。 */
    INVALID_TARGET_SELECTION("SCHEDULE_093", "予定対象者の指定が不正です", Severity.WARN),

    /** 指定された予定対象者が当該スコープの有効メンバーではない（存在秘匿）。 */
    SCHEDULE_TARGET_MEMBER_NOT_FOUND(
            "SCHEDULE_094", "指定された予定対象者が見つかりません", Severity.WARN),

    // --- F03.19 統合カレンダービュー: カレンダーレイヤー設定（設計書 §7） ---

    /** カレンダーレイヤー設定が見つからない（404）。 */
    CALENDAR_LAYER_NOT_FOUND(
            "SCHEDULE_100", "カレンダーレイヤー設定が見つかりません", Severity.WARN),

    /** 所属していないスコープのレイヤー設定変更（403）。存在しないIDも同じコードに畳んで存在秘匿する。 */
    CALENDAR_LAYER_NOT_MEMBER(
            "SCHEDULE_101", "所属していないスコープのレイヤー設定は変更できません", Severity.WARN),

    /** 色の指定が #RRGGBB 形式でない（422）。 */
    CALENDAR_LAYER_INVALID_COLOR(
            "SCHEDULE_102", "色の指定が不正です（#RRGGBB 形式で指定してください）", Severity.WARN),

    /** レイヤーのスコープ種別・スコープIDが不正（422）。 */
    CALENDAR_LAYER_INVALID_SCOPE(
            "SCHEDULE_103", "レイヤーのスコープ指定が不正です", Severity.WARN),

    /** 1ユーザーあたりのレイヤー設定行数が上限に達した（400。件数上限は .claudecode.md §3.2.1 の本則どおり既定の 400）。 */
    CALENDAR_LAYER_LIMIT_EXCEEDED(
            "SCHEDULE_104", "カレンダーレイヤー設定の上限（1000件）に達しています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
