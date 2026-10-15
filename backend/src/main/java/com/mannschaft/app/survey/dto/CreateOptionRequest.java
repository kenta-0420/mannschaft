package com.mannschaft.app.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 選択肢作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateOptionRequest {

    @NotBlank
    @Size(max = 200)
    private final String optionText;

    private final Integer displayOrder;
}
