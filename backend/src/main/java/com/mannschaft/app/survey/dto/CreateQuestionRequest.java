package com.mannschaft.app.survey.dto;

import com.mannschaft.app.survey.QuestionType;
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

    /** 設問種別。未知値は Jackson の束縛段階で弾かれ 400 となる（#2617-2）。 */
    @NotNull
    private final QuestionType questionType;

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
