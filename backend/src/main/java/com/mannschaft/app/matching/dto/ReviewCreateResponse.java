package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * レビュー作成レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReviewCreateResponse {

    private final Long id;
    private final Long revieweeTeamId;
    private final Short rating;
}
