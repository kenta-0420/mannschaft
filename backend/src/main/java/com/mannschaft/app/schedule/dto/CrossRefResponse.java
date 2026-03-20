package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * クロスリファレンスレスポンスDTO。チーム・組織間招待の状態を返す。
 */
@Getter
@RequiredArgsConstructor
public class CrossRefResponse {

    private final Long id;
    private final Long sourceScheduleId;
    private final String targetType;
    private final Long targetId;
    private final Long targetScheduleId;
    private final String status;
    private final String message;
    private final Long invitedBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime respondedAt;
}
