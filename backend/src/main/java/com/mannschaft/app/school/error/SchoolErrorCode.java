package com.mannschaft.app.school.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** F03.13 学校出欠管理機能のエラーコード定義。 */
@Getter
@RequiredArgsConstructor
public enum SchoolErrorCode implements ErrorCode {

    /** 学級担任設定が見つからない */
    HOMEROOM_NOT_FOUND("SCHOOL_HOMEROOM_NOT_FOUND", "学級担任設定が見つかりません", Severity.WARN),

    /** 同一チーム・年度に現役の学級担任設定が既に存在する */
    HOMEROOM_ALREADY_EXISTS("SCHOOL_HOMEROOM_ALREADY_EXISTS", "指定年度の学級担任設定が既に存在します", Severity.WARN),

    /** 日次出欠レコードが見つからない */
    DAILY_RECORD_NOT_FOUND("SCHOOL_DAILY_RECORD_NOT_FOUND", "日次出欠レコードが見つかりません", Severity.WARN),

    /** 同日の日次出欠レコードが既に存在する */
    DAILY_RECORD_DUPLICATE("SCHOOL_DAILY_RECORD_DUPLICATE", "指定日の日次出欠レコードが既に存在します", Severity.WARN),

    /** 時限別出欠レコードが見つからない */
    PERIOD_RECORD_NOT_FOUND("SCHOOL_PERIOD_RECORD_NOT_FOUND", "時限別出欠レコードが見つかりません", Severity.WARN),

    /** 保護者連絡が見つからない */
    FAMILY_NOTICE_NOT_FOUND("SCHOOL_FAMILY_NOTICE_NOT_FOUND", "保護者連絡が見つかりません", Severity.WARN),

    /** 保護者連絡は既に出欠に反映済み */
    FAMILY_NOTICE_ALREADY_APPLIED("SCHOOL_FAMILY_NOTICE_ALREADY_APPLIED", "保護者連絡は既に出欠に反映済みです", Severity.WARN),

    /** 移動検知アラートが見つからない */
    TRANSITION_ALERT_NOT_FOUND("SCHOOL_TRANSITION_ALERT_NOT_FOUND", "移動検知アラートが見つかりません", Severity.WARN),

    /** 移動検知アラートは既に解決済み */
    TRANSITION_ALERT_ALREADY_RESOLVED("SCHOOL_TRANSITION_ALERT_ALREADY_RESOLVED", "このアラートは既に解決済みです", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
