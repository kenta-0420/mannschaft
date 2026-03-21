package com.mannschaft.app.forms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * フォーム提出値リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SubmissionValueRequest {

    @NotBlank
    @Size(max = 50)
    private final String fieldKey;

    @NotBlank
    @Size(max = 20)
    private final String fieldType;

    private final String textValue;

    private final BigDecimal numberValue;

    private final LocalDate dateValue;

    @Size(max = 500)
    private final String fileKey;

    private final Boolean isAutoFilled;
}
