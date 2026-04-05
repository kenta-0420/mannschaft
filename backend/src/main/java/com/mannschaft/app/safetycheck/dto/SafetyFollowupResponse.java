package com.mannschaft.app.safetycheck.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フォローアップレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SafetyFollowupResponse {

    private final Long id;
    private final Long safetyResponseId;
    private final String followupStatus;
    private final Long assignedTo;
    private final String note;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
