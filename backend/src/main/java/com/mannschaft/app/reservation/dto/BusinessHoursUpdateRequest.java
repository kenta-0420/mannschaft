package com.mannschaft.app.reservation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 営業時間一括更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BusinessHoursUpdateRequest {

    @NotNull
    @Size(min = 1, max = 7)
    @Valid
    private final List<BusinessHourEntry> hours;
}
