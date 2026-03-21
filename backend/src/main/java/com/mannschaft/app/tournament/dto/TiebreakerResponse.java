package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイブレークレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TiebreakerResponse {

    private final Long id;
    private final Integer priority;
    private final String criteria;
    private final String direction;
}
