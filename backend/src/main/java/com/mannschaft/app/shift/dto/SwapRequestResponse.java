package com.mannschaft.app.shift.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * シフト交代リクエストレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SwapRequestResponse {

    private final Long id;
    private final Long slotId;
    private final Long requesterId;
    private final Long accepterId;
    private final String status;
    private final String reason;
    private final String adminNote;
    private final Long resolvedBy;
    private final LocalDateTime resolvedAt;
    private final LocalDateTime createdAt;
}
