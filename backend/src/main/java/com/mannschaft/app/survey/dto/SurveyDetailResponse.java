package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アンケート詳細レスポンスDTO。設問・選択肢を含む。
 */
@Getter
@RequiredArgsConstructor
public class SurveyDetailResponse {

    private final SurveyResponse survey;
    private final List<QuestionResponse> questions;
}
