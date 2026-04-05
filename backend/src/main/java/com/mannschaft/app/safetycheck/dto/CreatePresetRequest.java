package com.mannschaft.app.safetycheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メッセージプリセット作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreatePresetRequest {

    @NotBlank
    @Size(max = 200)
    private final String body;

    private final Integer sortOrder;
}
