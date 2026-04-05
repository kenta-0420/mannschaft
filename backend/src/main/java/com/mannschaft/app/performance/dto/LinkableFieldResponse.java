package com.mannschaft.app.performance.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 活動記録連携可能なカスタムフィールドレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class LinkableFieldResponse {

    private final Long id;
    private final String fieldName;
    private final String unit;
}
