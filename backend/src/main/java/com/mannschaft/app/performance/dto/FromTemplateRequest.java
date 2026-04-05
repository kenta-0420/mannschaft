package com.mannschaft.app.performance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * テンプレートからの指標一括作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class FromTemplateRequest {

    @NotBlank
    @Size(max = 50)
    private final String sportCategory;

    private final List<String> excludeNames;
}
