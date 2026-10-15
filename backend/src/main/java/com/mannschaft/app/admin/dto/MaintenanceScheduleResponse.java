package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * メンテナンススケジュールレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MaintenanceScheduleResponse {

    private final Long id;
    private final String title;
    private final String message;
    private final String mode;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
    private final String status;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
