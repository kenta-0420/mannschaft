package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 出欠回答レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AttendanceResponse {

    private final Long id;
    private final Long userId;
    private final String status;
    private final String comment;
    private final LocalDateTime respondedAt;
}
