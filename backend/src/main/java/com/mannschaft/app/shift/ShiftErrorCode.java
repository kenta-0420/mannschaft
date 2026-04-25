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
    INVALID_DATE_RANGE("SHIFT_010", "開始日は終了日より前である必要があります", Severity.ERROR),

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
    SWAP_SELF_REQUEST("SHIFT_016", "自分自身に交代リクエストを送ることはできません", Severity.ERROR),

    /** アサイン人数超過 */
    SLOT_ASSIGNMENT_EXCEEDED("SHIFT_017", "シフト枠の必要人数を超過しています", Severity.ERROR),

    /** 楽観的ロック競合 */
    OPTIMISTIC_LOCK_CONFLICT("SHIFT_018", "他のユーザーによって更新されています。再度お試しください", Severity.WARN),

    /** アクセス権なし */
    ACCESS_DENIED("SHIFT_019", "このシフトへのアクセス権がありません", Severity.WARN),

    /** 自動割当実行ログが見つからない */
    ASSIGNMENT_RUN_NOT_FOUND("SHIFT_020", "自動割当実行ログが見つかりません", Severity.WARN),

    /** 目視確認が完了していない（公開ゲート） */
    VISUAL_REVIEW_REQUIRED("SHIFT_021", "目視確認が完了していない割当提案があります。確認後に公開してください", Severity.WARN),

    /** 自動割当実行ログのステータスが不正 */
    INVALID_ASSIGNMENT_RUN_STATUS("SHIFT_022", "この操作は現在の実行ステータスでは実行できません", Severity.WARN),

    /** 勤務制約が見つからない */
    WORK_CONSTRAINT_NOT_FOUND("SHIFT_023", "勤務制約が見つかりません", Severity.WARN),

    /** 変更依頼が見つからない */
    CHANGE_REQUEST_NOT_FOUND("SHIFT_030", "シフト変更依頼が見つかりません", Severity.WARN),

    /** 変更依頼のステータスが不正 */
    INVALID_CHANGE_REQUEST_STATUS("SHIFT_031", "この操作は現在の変更依頼ステータスでは実行できません", Severity.WARN),

    /** オープンコールの月次上限超過 */
    OPEN_CALL_MONTHLY_LIMIT_EXCEEDED("SHIFT_032", "オープンコールは月3件までしか申請できません", Severity.WARN),

    /** 交代リクエストがオープンコールではない */
    NOT_OPEN_CALL("SHIFT_033", "この交代リクエストはオープンコールではありません", Severity.WARN),

    /** 既に手挙げ済みのオープンコール */
    OPEN_CALL_ALREADY_CLAIMED("SHIFT_034", "このオープンコールは既に手挙げされています", Severity.WARN),

    /** 候補者の選定権限なし */
    CLAIMER_SELECT_DENIED("SHIFT_035", "候補者選定はオープンコール申請者または管理者のみ実行できます", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
