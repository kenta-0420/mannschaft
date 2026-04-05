package com.mannschaft.app.survey.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 設問作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateQuestionRequest {

    @NotBlank
    private final String questionType;

    @NotBlank
    @Size(max = 500)
    private final String questionText;

    @NotNull
    private final Boolean isRequired;

    private final Integer displayOrder;

    private final Integer maxSelections;

    private final Integer scaleMin;

    private final Integer scaleMax;

    @Size(max = 50)
    private final String scaleMinLabel;

    @Size(max = 50)
    private final String scaleMaxLabel;

    @Valid
    private final List<CreateOptionRequest> options;
}
