package com.mannschaft.app.timetable;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 時間割管理モジュール固有のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum TimetableErrorCode implements ErrorCode {

    // ---- Not Found ----
    TIMETABLE_NOT_FOUND("TIMETABLE_001", "時間割が見つかりません", Severity.WARN),
    TERM_NOT_FOUND("TIMETABLE_002", "学期が見つかりません", Severity.WARN),
    SLOT_NOT_FOUND("TIMETABLE_003", "時間割スロットが見つかりません", Severity.WARN),
    CHANGE_NOT_FOUND("TIMETABLE_004", "臨時変更が見つかりません", Severity.WARN),

    // ---- Status Transition ----
    INVALID_STATUS_TRANSITION("TIMETABLE_010", "無効なステータス遷移です", Severity.WARN),
    TIMETABLE_NOT_DRAFT("TIMETABLE_011", "時間割が下書き状態ではありません", Severity.WARN),
    TIMETABLE_NOT_ACTIVE("TIMETABLE_012", "時間割が有効状態ではありません", Severity.WARN),
    TIMETABLE_NOT_ARCHIVED("TIMETABLE_013", "時間割がアーカイブ状態ではありません", Severity.WARN),

    // ---- Term Validation ----
    DUPLICATE_TERM_NAME("TIMETABLE_020", "同一年度に同名の学期が既に存在します", Severity.WARN),
    TERM_DATE_OVERLAP("TIMETABLE_021", "学期の日付範囲が他の学期と重複しています", Severity.WARN),
    TERM_HAS_TIMETABLES("TIMETABLE_022", "時間割が紐づいているため学期を削除できません", Severity.WARN),

    // ---- Period Validation ----
    PERIOD_OVERRIDE_REQUIRED("TIMETABLE_030", "時限設定が必要です", Severity.WARN),
    INVALID_PERIOD_OVERRIDE("TIMETABLE_031", "時限設定が不正です", Severity.WARN),
    BREAK_PERIOD_ASSIGNED("TIMETABLE_032", "休憩時限にはスロットを割り当てられません", Severity.WARN),
    SUBJECT_NAME_REQUIRED("TIMETABLE_033", "科目名は必須です", Severity.WARN),

    // ---- Effective Date Validation ----
    EFFECTIVE_DATE_OUT_OF_TERM("TIMETABLE_040", "有効期間が学期の範囲外です", Severity.WARN),
    ACTIVE_TIMETABLE_CONFLICT("TIMETABLE_041", "有効期間が重複する有効な時間割が存在します", Severity.WARN),

    // ---- Slot / Change Validation ----
    SLOT_WEEK_PATTERN_CONFLICT("TIMETABLE_050", "週パターンが競合しています", Severity.WARN),
    DAY_OFF_ALREADY_EXISTS("TIMETABLE_051", "指定日には既に休日が設定されています", Severity.WARN),
    CHANGE_DATE_OUT_OF_RANGE("TIMETABLE_052", "変更対象日が時間割の有効期間外です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
