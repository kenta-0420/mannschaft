package com.mannschaft.app.queue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * カウンター作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateCounterRequest {

    @NotNull
    private final Long categoryId;

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    private final String acceptMode;

    private final Short avgServiceMinutes;

    private final Boolean avgServiceMinutesManual;

    private final Short maxQueueSize;

    private final LocalTime operatingTimeFrom;

    private final LocalTime operatingTimeTo;

    private final Short displayOrder;
}
