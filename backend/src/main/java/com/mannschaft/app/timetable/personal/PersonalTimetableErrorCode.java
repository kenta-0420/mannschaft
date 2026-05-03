package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.15 個人時間割モジュール固有のエラーコード。
 *
 * <p>HttpStatus マッピングは GlobalExceptionHandler.ERROR_CODE_STATUS_MAP に登録する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum PersonalTimetableErrorCode implements ErrorCode {

    // ---- Not Found（IDOR 対策で 404 統一） ----
    PERSONAL_TIMETABLE_NOT_FOUND("PERSONAL_TIMETABLE_001", "個人時間割が見つかりません", Severity.WARN),

    // ---- 上限到達（409） ----
    PERSONAL_TIMETABLE_LIMIT_EXCEEDED("PERSONAL_TIMETABLE_010", "個人時間割は1ユーザー5件までです", Severity.WARN),

    // ---- ステータス遷移（409） ----
    PERSONAL_TIMETABLE_NOT_DRAFT("PERSONAL_TIMETABLE_020", "個人時間割が下書き状態ではありません", Severity.WARN),
    PERSONAL_TIMETABLE_NOT_ACTIVE("PERSONAL_TIMETABLE_021", "個人時間割が有効状態ではありません", Severity.WARN),
    PERSONAL_TIMETABLE_NOT_ARCHIVED("PERSONAL_TIMETABLE_022", "個人時間割がアーカイブ状態ではありません", Severity.WARN),
    PERSONAL_TIMETABLE_INVALID_STATUS_TRANSITION("PERSONAL_TIMETABLE_023", "無効なステータス遷移です", Severity.WARN),

    // ---- 入力検証（400 / 422） ----
    PERSONAL_TIMETABLE_INVALID_DATE_RANGE("PERSONAL_TIMETABLE_030", "適用開始日は適用終了日以前である必要があります", Severity.WARN),
    PERSONAL_TIMETABLE_WEEK_PATTERN_BASE_REQUIRED("PERSONAL_TIMETABLE_031", "週パターン有効時は基準日が必須です", Severity.WARN),
    PERSONAL_TIMETABLE_WEEK_PATTERN_BASE_OUT_OF_RANGE("PERSONAL_TIMETABLE_032", "週パターン基準日は適用期間内である必要があります", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
