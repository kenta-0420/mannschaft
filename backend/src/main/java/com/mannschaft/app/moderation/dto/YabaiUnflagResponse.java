package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ヤバいやつ解除申請レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class YabaiUnflagResponse {

    private final Long id;
    private final Long userId;
    private final String reason;
    private final String status;
    private final Long reviewedBy;
    private final String reviewNote;
    private final LocalDateTime reviewedAt;
    private final LocalDateTime nextEligibleAt;
    private final LocalDateTime createdAt;
}
