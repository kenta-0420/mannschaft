package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通報対応アクションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReportActionResponse {

    private final Long id;
    private final Long reportId;
    private final String actionType;
    private final Long actionBy;
    private final String note;
    private final LocalDateTime freezeUntil;
    private final String guidelineSection;
    private final LocalDateTime createdAt;
}
