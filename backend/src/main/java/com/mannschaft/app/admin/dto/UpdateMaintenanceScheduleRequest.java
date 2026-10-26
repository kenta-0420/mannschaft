package com.mannschaft.app.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * メンテナンススケジュール更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateMaintenanceScheduleRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String message;

    private final String mode;

    @NotNull
    private final LocalDateTime startsAt;

    @NotNull
    private final LocalDateTime endsAt;
}
