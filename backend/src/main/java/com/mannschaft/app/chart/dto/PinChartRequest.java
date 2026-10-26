package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ピン留めリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PinChartRequest {

    @NotNull
    private final Boolean isPinned;
}
