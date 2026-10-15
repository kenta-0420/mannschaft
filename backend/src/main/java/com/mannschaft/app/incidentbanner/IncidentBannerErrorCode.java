package com.mannschaft.app.incidentbanner;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F12.5 障害告知バナーのエラーコード定義。
 *
 * <p>シスアド手動オーサリングのバナー管理で発生するエラーを表す。</p>
 */
@Getter
@RequiredArgsConstructor
public enum IncidentBannerErrorCode implements ErrorCode {

    /** バナーが見つからない */
    INCIDENT_BANNER_NOT_FOUND("INCIDENT_BANNER_001", "障害告知バナーが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
