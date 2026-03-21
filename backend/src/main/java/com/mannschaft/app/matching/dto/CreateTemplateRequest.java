package com.mannschaft.app.matching.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateTemplateRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    @NotNull
    private final String templateJson;
}
