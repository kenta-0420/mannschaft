package com.mannschaft.app.committee.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 議事録確定レスポンスDTO。
 */
@Getter
@Builder
public class MinutesConfirmResponse {

    /** 更新された活動記録のID */
    private final Long activityRecordId;

    /** 更新後の fieldValues JSON 文字列（_meta.status = "CONFIRMED" を含む） */
    private final String fieldValues;
}
