package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * アンケート統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SurveyStatsResponse {

    private final long total;
    private final long draft;
    private final long published;
    private final long closed;
    private final long archived;
}
