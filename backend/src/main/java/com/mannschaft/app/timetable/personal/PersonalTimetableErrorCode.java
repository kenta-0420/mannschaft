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
    PERSONAL_TIMETABLE_WEEK_PATTERN_BASE_OUT_OF_RANGE("PERSONAL_TIMETABLE_032", "週パターン基準日は適用期間内である必要があります", Severity.WARN),

    // ---- 時限定義（Phase 2） ----
    PERSONAL_PERIOD_LIMIT_EXCEEDED("PERSONAL_TIMETABLE_040", "時限は最大15件までです", Severity.WARN),
    PERSONAL_PERIOD_INVALID_TIME_RANGE("PERSONAL_TIMETABLE_041", "時限の開始時刻は終了時刻より前である必要があります", Severity.WARN),
    PERSONAL_PERIOD_NUMBER_DUPLICATED("PERSONAL_TIMETABLE_042", "時限番号が重複しています", Severity.WARN),
    PERSONAL_PERIOD_NUMBER_OUT_OF_RANGE("PERSONAL_TIMETABLE_043", "時限番号は1〜15の範囲で指定してください", Severity.WARN),
    PERSONAL_TIMETABLE_NOT_EDITABLE("PERSONAL_TIMETABLE_044", "個人時間割が下書き状態でないため編集できません", Severity.WARN),

    // ---- コマ（Phase 2） ----
    PERSONAL_SLOT_LIMIT_EXCEEDED("PERSONAL_TIMETABLE_050", "コマは最大100件までです", Severity.WARN),
    PERSONAL_SLOT_BREAK_PERIOD_ASSIGNED("PERSONAL_TIMETABLE_051", "休憩時限にはコマを割り当てられません", Severity.WARN),
    PERSONAL_SLOT_PERIOD_NOT_FOUND("PERSONAL_TIMETABLE_052", "指定された時限が定義されていません", Severity.WARN),
    PERSONAL_SLOT_WEEK_PATTERN_CONFLICT("PERSONAL_TIMETABLE_053", "EVERY と A/B 週の同時登録はできません", Severity.WARN),
    PERSONAL_SLOT_WEEK_PATTERN_NOT_ENABLED("PERSONAL_TIMETABLE_054", "週パターン無効時は EVERY のみ指定できます", Severity.WARN),
    PERSONAL_SLOT_DUPLICATED("PERSONAL_TIMETABLE_055", "同一の曜日×時限×週パターンのコマが重複しています", Severity.WARN),
    PERSONAL_SLOT_LINK_NOT_SUPPORTED_YET("PERSONAL_TIMETABLE_056", "コマのチームリンク機能は Phase 4 で提供予定です", Severity.WARN),

    // ---- 個人メモ（Phase 3） ----
    NOTE_NOT_FOUND("PERSONAL_TIMETABLE_060", "メモが見つかりません", Severity.WARN),
    NOTE_PRECONDITION_FAILED("PERSONAL_TIMETABLE_061", "メモが他で更新されています（再読込してください）", Severity.WARN),
    NOTE_UNSAFE_MARKDOWN("PERSONAL_TIMETABLE_062", "メモに不正なコンテンツが含まれています", Severity.WARN),
    NOTE_FIELD_TOO_LONG("PERSONAL_TIMETABLE_063", "メモの文字数が上限を超えています", Severity.WARN),
    NOTE_INVALID_SLOT_KIND("PERSONAL_TIMETABLE_064", "slot_kind は TEAM または PERSONAL を指定してください", Severity.WARN),
    NOTE_SLOT_NOT_OWNED("PERSONAL_TIMETABLE_065", "対象のスロットにアクセスできません", Severity.WARN),
    NOTE_TEAM_NOT_MEMBER("PERSONAL_TIMETABLE_066", "対象チームのメンバーではありません", Severity.WARN),

    // ---- カスタムフィールド（Phase 3） ----
    NOTE_FIELD_NOT_FOUND("PERSONAL_TIMETABLE_070", "カスタム項目が見つかりません", Severity.WARN),
    NOTE_FIELD_LIMIT_EXCEEDED("PERSONAL_TIMETABLE_071", "カスタム項目は最大10件までです", Severity.WARN),
    NOTE_FIELD_LABEL_DUPLICATED("PERSONAL_TIMETABLE_072", "同名のカスタム項目が既に存在します", Severity.WARN),
    NOTE_FIELD_INVALID_MAX_LENGTH("PERSONAL_TIMETABLE_073", "max_length は 500 / 2000 / 5000 のいずれかを指定してください", Severity.WARN),

    // ---- 添付ファイル（Phase 3） ----
    ATTACHMENT_NOT_FOUND("PERSONAL_TIMETABLE_080", "添付ファイルが見つかりません", Severity.WARN),
    ATTACHMENT_LIMIT_EXCEEDED("PERSONAL_TIMETABLE_081", "1メモあたりの添付ファイルは最大5件までです", Severity.WARN),
    ATTACHMENT_SIZE_EXCEEDED("PERSONAL_TIMETABLE_082", "添付ファイルのサイズが上限（5MB）を超えています", Severity.WARN),
    ATTACHMENT_UNSUPPORTED_TYPE("PERSONAL_TIMETABLE_083", "サポートされていないファイル形式です", Severity.WARN),
    ATTACHMENT_QUOTA_EXCEEDED("PERSONAL_TIMETABLE_084", "ストレージ容量の上限（100MB）に達しています", Severity.WARN),
    ATTACHMENT_MAGIC_BYTE_MISMATCH("PERSONAL_TIMETABLE_085", "ファイル内容が宣言された形式と一致しません", Severity.WARN),
    ATTACHMENT_OBJECT_NOT_FOUND("PERSONAL_TIMETABLE_086", "アップロードされたファイルが R2 に存在しません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

