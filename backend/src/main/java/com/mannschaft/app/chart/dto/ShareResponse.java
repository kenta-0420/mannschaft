package com.mannschaft.app.chart.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 共有設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShareResponse {

    private final Long id;
    private final Boolean isSharedToCustomer;
}
