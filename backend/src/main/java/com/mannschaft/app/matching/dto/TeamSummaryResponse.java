package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チームサマリーレスポンスDTO。募集一覧でチーム情報を表示するためのもの。
 */
@Getter
@RequiredArgsConstructor
public class TeamSummaryResponse {

    private final Long id;
    private final String name;
    private final Double averageRating;
    private final Long reviewCount;
    private final Long cancelCount;
}
