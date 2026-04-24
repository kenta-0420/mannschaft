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

    /** 勤務制約が見つからない（v2 新規） */
    WORK_CONSTRAINT_NOT_FOUND("SHIFT_020", "勤務制約が見つかりません", Severity.WARN),

    /** 勤務制約の全項目が NULL（v2 新規） */
    WORK_CONSTRAINT_ALL_NULL("SHIFT_021", "少なくとも1つの勤務制約項目を指定してください", Severity.WARN),

    /** 勤務制約の管理権限なし（v2 新規） */
    WORK_CONSTRAINT_FORBIDDEN("SHIFT_022", "勤務制約を管理する権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
