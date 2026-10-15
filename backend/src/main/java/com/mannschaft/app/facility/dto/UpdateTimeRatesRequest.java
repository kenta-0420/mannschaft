package com.mannschaft.app.facility.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 時間帯別料金一括置換リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateTimeRatesRequest {

    @NotEmpty
    @Valid
    private final List<TimeRateEntry> rates;
}
