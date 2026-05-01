package com.mannschaft.app.actionmemo;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F02.5 行動メモのエラーコード定義。
 *
 * <p>設計書 §6 認可チェックに従い、所有者不一致や存在しないリソースは
 * すべて {@link #ACTION_MEMO_NOT_FOUND} を返す（IDOR 対策で 403 ではなく 404）。
 * HttpStatus マッピングは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} にて
 * 404 に個別マッピングされる。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ActionMemoErrorCode implements ErrorCode {

    /** 行動メモが見つからない（所有者不一致 / 存在しない / 論理削除済み） */
    ACTION_MEMO_NOT_FOUND("ACTION_MEMO_001", "行動メモが見つかりません", Severity.WARN),

    /** 本文が空 */
    ACTION_MEMO_CONTENT_EMPTY("ACTION_MEMO_002", "メモ本文を入力してください", Severity.WARN),

    /** 本文が 5,000 文字超過 */
    ACTION_MEMO_CONTENT_TOO_LONG("ACTION_MEMO_003", "メモ本文は5,000文字以内で入力してください", Severity.WARN),

    /** memo_date が未来日付 */
    ACTION_MEMO_FUTURE_DATE("ACTION_MEMO_004", "未来の日付には書けません", Severity.WARN),

    /** 1日 200 件上限超過 */
    ACTION_MEMO_DAILY_LIMIT_EXCEEDED("ACTION_MEMO_005",
            "本日の上限（200件）に達しました。明日以降にお書きください", Severity.WARN),

    /** 紐付けようとした TODO が見つからない（所有者不一致・PERSONAL 以外を含む） */
    ACTION_MEMO_TODO_NOT_FOUND("ACTION_MEMO_006",
            "紐付けようとした TODO が見つかりません", Severity.WARN),

    /** 1日の対象日付にメモが0件（publish-daily で使用。Phase 2 で実装） */
    ACTION_MEMO_NO_MEMOS_FOR_DATE("ACTION_MEMO_007",
            "該当日のメモがありません", Severity.WARN),

    /** 行動メモタグが見つからない（所有者不一致含む。Phase 4 で使用） */
    ACTION_MEMO_TAG_NOT_FOUND("ACTION_MEMO_008", "タグが見つかりません", Severity.WARN),

    /** 1ユーザーあたりのタグ上限（100件）超過（Phase 4） */
    ACTION_MEMO_TAG_LIMIT_EXCEEDED("ACTION_MEMO_009",
            "タグの上限（100件）に達しました。不要なタグを削除してから作成してください", Severity.WARN),

    /** 1メモあたりのタグ数上限（10個）超過（Phase 4） */
    ACTION_MEMO_TAG_PER_MEMO_LIMIT_EXCEEDED("ACTION_MEMO_010",
            "1メモあたりのタグは10個までです", Severity.WARN),

    // ==================================================================
    // Phase 3 エラーコード
    // ==================================================================

    /** duration_minutes が範囲外（0〜1440 以外）*/
    ACTION_MEMO_INVALID_DURATION("ACTION_MEMO_011",
            "実績時間は0〜1440分（0〜24時間）で入力してください", Severity.WARN),

    /** progress_rate が範囲外（0.00〜100.00 以外）*/
    ACTION_MEMO_INVALID_PROGRESS_RATE("ACTION_MEMO_012",
            "進捗率は0.00〜100.00の範囲で入力してください", Severity.WARN),

    /** progress_rate 指定時に related_todo_id が未設定 */
    ACTION_MEMO_PROGRESS_REQUIRES_TODO("ACTION_MEMO_013",
            "進捗率を入力する場合は関連TODOを指定してください", Severity.WARN),

    /** completes_todo = true 時に related_todo_id が未設定 */
    ACTION_MEMO_COMPLETES_REQUIRES_TODO("ACTION_MEMO_014",
            "TODO完了フラグを使う場合は関連TODOを指定してください", Severity.WARN),

    /** WORK カテゴリ以外のメモをチーム投稿しようとした */
    ACTION_MEMO_ONLY_WORK_CAN_BE_POSTED("ACTION_MEMO_015",
            "仕事（WORK）カテゴリのメモのみチームタイムラインに投稿できます", Severity.WARN),

    /** 既にチーム投稿済みのメモに再投稿しようとした */
    ACTION_MEMO_ALREADY_POSTED("ACTION_MEMO_016",
            "このメモはすでにチームタイムラインに投稿済みです", Severity.WARN),

    /** publish-to-team で team_id も default_post_team_id も NULL */
    ACTION_MEMO_TEAM_ID_REQUIRED("ACTION_MEMO_017",
            "投稿先チームを指定してください（設定からデフォルトチームを設定することもできます）",
            Severity.WARN),

    /** publish-daily-to-team で当日の WORK メモが0件 */
    ACTION_MEMO_NO_WORK_MEMO_TODAY("ACTION_MEMO_018",
            "今日の仕事メモがありません", Severity.WARN),

    /** publish-to-team で指定チームにメンバー登録がない（IDOR 対策で 404 扱い） */
    ACTION_MEMO_TEAM_NOT_FOUND("ACTION_MEMO_019",
            "投稿先チームが見つかりません", Severity.WARN),

    /** settings.default_post_team_id に所属していないチームを指定しようとした */
    ACTION_MEMO_INVALID_DEFAULT_TEAM("ACTION_MEMO_020",
            "デフォルト投稿先チームに所属していません", Severity.WARN),

    // ==================================================================
    // Phase 4-α エラーコード
    // ==================================================================

    /** 組織スコープ投稿時に指定組織に所属していない（IDOR 対策で 404 扱い） */
    ACTION_MEMO_ORG_NOT_FOUND("ACTION_MEMO_021",
            "指定した組織が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
