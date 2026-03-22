package com.mannschaft.app.schedule;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.10 年間行事計画のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleEventCategoryErrorCode implements ErrorCode {

    /** カテゴリが見つからない */
    CATEGORY_NOT_FOUND("EVTCAT_001", "行事カテゴリが見つかりません", Severity.WARN),

    /** カテゴリ名重複 */
    DUPLICATE_CATEGORY_NAME("EVTCAT_002", "同じ名前のカテゴリが既に存在します", Severity.WARN),

    /** カテゴリ数上限超過 */
    CATEGORY_LIMIT_EXCEEDED("EVTCAT_003", "カテゴリ数の上限（30件）に達しています", Severity.WARN),

    /** カテゴリスコープ不整合 */
    CATEGORY_SCOPE_MISMATCH("EVTCAT_010", "スケジュールのスコープとカテゴリのスコープが一致しません", Severity.ERROR),

    /** 年間コピー同一年度 */
    ANNUAL_COPY_SAME_YEAR("EVTCAT_020", "コピー元とコピー先の年度が同じです", Severity.ERROR),

    /** 年間コピーソース不在 */
    ANNUAL_COPY_SOURCE_NOT_FOUND("EVTCAT_021", "コピー元のスケジュールが見つかりません", Severity.WARN),

    /** 年間コピー重複 */
    ANNUAL_COPY_CONFLICT("EVTCAT_022", "コピー先に同じタイトル・日付のスケジュールが既に存在します", Severity.WARN),

    /** 年間コピー上限超過 */
    ANNUAL_COPY_LIMIT_EXCEEDED("EVTCAT_023", "一度にコピーできるスケジュール数の上限に達しています", Severity.WARN),

    /** 年度と日付の不整合 */
    ACADEMIC_YEAR_DATE_MISMATCH("EVTCAT_030", "開始日時が指定された年度の範囲外です", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
