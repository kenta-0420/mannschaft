package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 都道府県レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class PrefectureResponse {

    private final String code;
    private final String name;
}
