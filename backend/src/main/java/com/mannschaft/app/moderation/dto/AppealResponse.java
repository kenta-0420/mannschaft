package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 異議申立てレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AppealResponse {

    private final Long id;
    private final Long userId;
    private final Long reportId;
    private final Long actionId;
    private final String status;
    private final String appealReason;
    private final Long reviewedBy;
    private final String reviewNote;
    private final LocalDateTime reviewedAt;
    private final LocalDateTime createdAt;
}
