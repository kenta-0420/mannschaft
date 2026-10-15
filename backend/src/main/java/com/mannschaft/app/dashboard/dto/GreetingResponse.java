package com.mannschaft.app.dashboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 挨拶ヘッダーレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class GreetingResponse {

    private final String message;
    private final String summary;
}
