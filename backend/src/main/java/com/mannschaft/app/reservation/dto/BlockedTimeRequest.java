package com.mannschaft.app.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * ブロック時間作成・更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BlockedTimeRequest {

    @NotNull
    private final LocalDate blockedDate;

    private final LocalTime startTime;

    private final LocalTime endTime;

    @Size(max = 200)
    private final String reason;
}
