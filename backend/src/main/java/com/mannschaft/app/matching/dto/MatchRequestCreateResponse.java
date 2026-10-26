package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 募集作成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class MatchRequestCreateResponse {

    private final Long id;
    private final String status;
}
