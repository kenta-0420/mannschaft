package com.mannschaft.app.forms.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォームプリセット更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateFormPresetRequest {

    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 50)
    private final String category;

    private final String fieldsJson;

    @Size(max = 50)
    private final String icon;

    @Size(max = 7)
    private final String color;
}
