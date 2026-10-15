package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 市区町村レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CityResponse {

    private final String code;
    private final String name;
}
