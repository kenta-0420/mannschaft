package com.mannschaft.app.chart.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 顧客共有設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShareChartRequest {

    @NotNull
    private final Boolean isSharedToCustomer;
}
