package com.mannschaft.app.schedule;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.17 キープ（日付未定の予定）のエラーコード定義。
 *
 * <p>設計書 {@code docs/features/F03.17_schedule_keep.md} §7。
 * 既存 {@link ScheduleErrorCode} は SCHEDULE_092 まで使用済みの大所帯であり、
 * キープは独立した新機能なので専用 enum に番号空間を分ける
 * （{@code ScheduleEventCategoryErrorCode}・{@code GoogleCalendarErrorCode} の前例に倣う）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleKeepErrorCode implements ErrorCode {

    /** 存在しない／論理削除済み／非メンバーからのアクセス／パスとレコードのスコープ不一致（IDOR）／reorder の不正 ID */
    KEEP_NOT_FOUND("SCHEDULE_KEEP_001", "キープが見つかりません", Severity.WARN),

    /** title が空・空白のみ・200文字超 */
    KEEP_TITLE_REQUIRED("SCHEDULE_KEEP_002", "タイトルを入力してください", Severity.WARN),

    /** candidateDates 11件以上 */
    KEEP_TOO_MANY_CANDIDATE_DATES("SCHEDULE_KEEP_003", "候補日は最大10件です", Severity.WARN),

    /** 候補日の形式不正 */
    KEEP_INVALID_CANDIDATE_DATE("SCHEDULE_KEEP_004", "候補日の形式が正しくありません", Severity.WARN),

    /** メンバーだが作成者でも ADMIN でもない者の編集／削除／revert／archive／restore */
    KEEP_FORBIDDEN("SCHEDULE_KEEP_005", "このキープを操作する権限がありません", Severity.WARN),

    /** SCHEDULED への再 convert（予定二重生成を塞ぐ本体） */
    KEEP_NOT_CONVERTIBLE("SCHEDULE_KEEP_006", "このキープは予定に変換できません", Severity.WARN),

    /** SCHEDULED で title/candidateDates を PATCH／ARCHIVED への任意の PATCH */
    KEEP_NOT_EDITABLE("SCHEDULE_KEEP_007", "この状態では編集できません", Severity.WARN),

    /** revert 時に変換先 schedules に出欠回答が存在 */
    KEEP_REVERT_BLOCKED_BY_ATTENDANCE(
            "SCHEDULE_KEEP_008", "出欠の回答があるため変換を取り消せません", Severity.WARN),

    /** ARCHIVED への convert／取り消す対象が無い revert（conv_id が NULL） */
    KEEP_INVALID_STATE_TRANSITION("SCHEDULE_KEEP_009", "この操作はできません", Severity.WARN),

    /** スコープあたりの件数上限超過（§10.1） */
    KEEP_LIMIT_EXCEEDED("SCHEDULE_KEEP_010", "キープの上限に達しました", Severity.WARN),

    // SCHEDULE_KEEP_011 は欠番（旧 KEEP_RESTORE_REQUIRES_REVERT。§5.3.1 の裁定変更により廃止・§7.2）。

    /** orderedIds の重複／件数超過／ARCHIVED の混入 */
    KEEP_INVALID_REORDER("SCHEDULE_KEEP_012", "並び替えの指定が正しくありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
