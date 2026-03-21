package com.mannschaft.app.forms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォームプリセット作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateFormPresetRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 50)
    private final String category;

    @NotNull
    private final String fieldsJson;

    @Size(max = 50)
    private final String icon;

    @Size(max = 7)
    private final String color;
}
