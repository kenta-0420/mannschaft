package com.mannschaft.app.shift;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.5 シフト管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ShiftErrorCode implements ErrorCode {

    /** シフトスケジュールが見つからない */
    SHIFT_SCHEDULE_NOT_FOUND("SHIFT_001", "シフトスケジュールが見つかりません", Severity.WARN),

    /** シフト枠が見つからない */
    SHIFT_SLOT_NOT_FOUND("SHIFT_002", "シフト枠が見つかりません", Severity.WARN),

    /** シフト希望が見つからない */
    SHIFT_REQUEST_NOT_FOUND("SHIFT_003", "シフト希望が見つかりません", Severity.WARN),

    /** シフトポジションが見つからない */
    SHIFT_POSITION_NOT_FOUND("SHIFT_004", "シフトポジションが見つかりません", Severity.WARN),

    /** 交代リクエストが見つからない */
    SWAP_REQUEST_NOT_FOUND("SHIFT_005", "交代リクエストが見つかりません", Severity.WARN),

    /** 開始日と終了日の整合性エラー */
    INVALID_DATE_RANGE("SHIFT_010", "開始日は終了日より前である必要があります", Severity.WARN),

    /** 希望提出期限超過 */
    REQUEST_DEADLINE_PASSED("SHIFT_011", "希望提出期限を過ぎています", Severity.WARN),

    /** シフトスケジュールのステータスが不正 */
    INVALID_SCHEDULE_STATUS("SHIFT_012", "この操作は現在のステータスでは実行できません", Severity.WARN),

    /** 交代リクエストのステータスが不正 */
    INVALID_SWAP_STATUS("SHIFT_013", "この操作は現在の交代リクエストステータスでは実行できません", Severity.WARN),

    /** 重複するポジション名 */
    POSITION_NAME_DUPLICATE("SHIFT_014", "同名のポジションが既に存在します", Severity.WARN),

    /** 重複する希望提出 */
    REQUEST_ALREADY_EXISTS("SHIFT_015", "既に希望を提出済みです", Severity.WARN),

    /** 自分自身への交代リクエスト */
    SWAP_SELF_REQUEST("SHIFT_016", "自分自身に交代リクエストを送ることはできません", Severity.WARN),

    /** アサイン人数超過 */
    SLOT_ASSIGNMENT_EXCEEDED("SHIFT_017", "シフト枠の必要人数を超過しています", Severity.WARN),

    /** 楽観的ロック競合 */
    OPTIMISTIC_LOCK_CONFLICT("SHIFT_018", "他のユーザーによって更新されています。再度お試しください", Severity.WARN),

    /** アクセス権なし */
    ACCESS_DENIED("SHIFT_019", "このシフトへのアクセス権がありません", Severity.WARN),

    /** 勤務制約が見つからない（v2 新規） */
    WORK_CONSTRAINT_NOT_FOUND("SHIFT_020", "勤務制約が見つかりません", Severity.WARN),

    /** 勤務制約の全項目が NULL（v2 新規） */
    WORK_CONSTRAINT_ALL_NULL("SHIFT_021", "少なくとも1つの勤務制約項目を指定してください", Severity.WARN),

    /** 勤務制約の管理権限なし（v2 新規） */
    WORK_CONSTRAINT_FORBIDDEN("SHIFT_022", "勤務制約を管理する権限がありません", Severity.WARN),

    /** 自動割当実行ログが見つからない */
    ASSIGNMENT_RUN_NOT_FOUND("SHIFT_024", "自動割当実行ログが見つかりません", Severity.WARN),

    /** 目視確認が完了していない（公開ゲート） */
    VISUAL_REVIEW_REQUIRED("SHIFT_025", "目視確認が完了していない割当提案があります。確認後に公開してください", Severity.WARN),

    /** 自動割当実行ログのステータスが不正 */
    INVALID_ASSIGNMENT_RUN_STATUS("SHIFT_026", "この操作は現在の実行ステータスでは実行できません", Severity.WARN),

    /** 変更依頼が見つからない */
    CHANGE_REQUEST_NOT_FOUND("SHIFT_030", "シフト変更依頼が見つかりません", Severity.WARN),

    /** 変更依頼のステータスが不正 */
    INVALID_CHANGE_REQUEST_STATUS("SHIFT_031", "この操作は現在の変更依頼ステータスでは実行できません", Severity.WARN),

    /** オープンコールの月次上限超過 */
    OPEN_CALL_MONTHLY_LIMIT_EXCEEDED("SHIFT_032", "オープンコールは月3件までしか申請できません", Severity.WARN),

    /** 手動リマインドの連打防止スロットリング（Valkey 同時実行ロック取得失敗） */
    MANUAL_REMINDER_THROTTLED("SHIFT_036", "リマインドは連続して送信できません。15 秒ほど待ってから再操作してください", Severity.WARN),

    /**
     * 希望提出の {@code slotId} と {@code slotDate} が食い違う（設計 §11.5.1.1-2）。
     *
     * <p>越境ではなく<b>クライアントの自己矛盾</b>なので 400 とする
     *（{@code Severity.WARN} の既定が 400 のため {@code GlobalExceptionHandler} への登録は不要）。
     * 越境（他 schedule 配下の枠・存在しない枠）は {@link #ACCESS_DENIED}（403）で畳む。</p>
     */
    REQUEST_SLOT_DATE_MISMATCH("SHIFT_037", "指定された枠の日付と希望日が一致しません", Severity.WARN),

    /** 枠時刻の前後関係が不正（F03.5 §11.2.5・400） */
    INVALID_TIME_RANGE("SHIFT_040", "開始時刻と終了時刻の組み合わせが正しくありません。日をまたぐ枠は「翌日終了」を指定し、またがない枠は開始時刻を終了時刻より前にしてください", Severity.WARN),

    /** 枠時刻の刻み・枠長が不正（F03.5 §11.2.5・400） */
    INVALID_SLOT_GRANULARITY("SHIFT_041", "シフト枠は15分単位で、最小15分以上24時間未満である必要があります", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
