package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ピン留めレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PinResponse {

    private final Long id;
    private final Boolean isPinned;
}
