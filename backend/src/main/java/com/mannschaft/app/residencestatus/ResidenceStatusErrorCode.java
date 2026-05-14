package com.mannschaft.app.residencestatus;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.16 居住実態管理・見守りのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ResidenceStatusErrorCode implements ErrorCode {

    ANNUAL_REVIEW_NOT_FOUND("RESIDENCE_STATUS_001", "年次更新キャンペーンが見つかりません", Severity.WARN),
    ANNUAL_REVIEW_ALREADY_CLOSED("RESIDENCE_STATUS_002", "年次更新キャンペーンはすでにクローズ済みです", Severity.WARN),
    ANNUAL_REVIEW_YEAR_CONFLICT("RESIDENCE_STATUS_003", "同じ年度のキャンペーンが既に存在します", Severity.WARN),
    ANNUAL_REVIEW_RESPONSE_NOT_FOUND("RESIDENCE_STATUS_004", "年次更新回答が見つかりません", Severity.WARN),
    RESIDENCE_STATE_INVALID("RESIDENCE_STATUS_005", "無効な居住状態です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
