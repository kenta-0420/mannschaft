package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アンケート設問作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSurveyRequest {

    @NotBlank
    @Size(max = 500)
    private final String question;

    @NotNull
    private final String questionType;

    private final List<String> options;

    @NotNull
    private final Boolean isRequired;

    @Min(0)
    private final Integer sortOrder;
}
