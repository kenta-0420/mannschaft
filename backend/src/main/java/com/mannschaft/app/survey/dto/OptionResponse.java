package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 選択肢レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class OptionResponse {

    private final Long id;
    private final Long questionId;
    private final String optionText;
    private final Integer displayOrder;
}
