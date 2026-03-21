package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * カルテコピーリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CopyChartRequest {

    private final LocalDate visitDate;

    private final Long staffUserId;
}
