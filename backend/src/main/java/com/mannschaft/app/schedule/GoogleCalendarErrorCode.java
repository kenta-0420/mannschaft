package com.mannschaft.app.schedule;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.3 Google Calendar同期・iCal配信のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum GoogleCalendarErrorCode implements ErrorCode {

    /** Google Calendar未連携 */
    GOOGLE_CALENDAR_NOT_CONNECTED("GCAL_001", "Google Calendarが連携されていません", Severity.WARN),

    /** Google Calendar連携が無効 */
    GOOGLE_CALENDAR_INACTIVE("GCAL_002", "Google Calendar連携が無効です", Severity.WARN),

    /** Google OAuth認証失敗 */
    GOOGLE_OAUTH_FAILED("GCAL_003", "Google OAuth認証に失敗しました", Severity.ERROR),

    /** Google Calendar API呼び出し失敗 */
    GOOGLE_API_ERROR("GCAL_004", "Google Calendar APIの呼び出しに失敗しました", Severity.ERROR),

    /** iCalトークンが見つからない */
    ICAL_TOKEN_NOT_FOUND("GCAL_005", "iCalトークンが見つかりません", Severity.WARN),

    /** iCalトークンが無効 */
    ICAL_TOKEN_INVALID("GCAL_006", "iCalトークンが無効です", Severity.WARN),

    /** 同期設定が見つからない */
    SYNC_SETTING_NOT_FOUND("GCAL_007", "同期設定が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
