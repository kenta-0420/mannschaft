package com.mannschaft.app.tournament.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 個人成績項目定義レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class StatDefResponse {

    private final Long id;
    private final String name;
    private final String statKey;
    private final String unit;
    private final String dataType;
    private final String aggregationType;
    private final Boolean isRankingTarget;
    private final String rankingLabel;
    private final Integer sortOrder;
}
